package main

import (
	"context"
	"crypto/md5"
	"embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	qrcode "github.com/skip2/go-qrcode"
)

//go:embed static
var staticFS embed.FS

const (
	defaultMainPort = 5666
	cliTimeout      = 120 * time.Second
)

var sessionErrRe = regexp.MustCompile(`(?i)session|登录|auth|login`)

// ---------------------------------------------------------------------------
// trim-cli 执行
// ---------------------------------------------------------------------------

func cliPath() string {
	if p := os.Getenv("FNOS_CLI"); p != "" {
		return p
	}
	exe, err := os.Executable()
	if err == nil {
		cand := filepath.Join(filepath.Dir(exe), "trim-cli")
		if _, err := os.Stat(cand); err == nil {
			return cand
		}
	}
	return "trim-cli" // fallback: PATH
}

func sessionEnv(host string) (string, []string, error) {
	sum := md5.Sum([]byte(host))
	tag := hex.EncodeToString(sum[:])[:12]
	cfgDir := filepath.Join(sessionRoot(), tag)
	if err := os.MkdirAll(cfgDir, 0o755); err != nil {
		return "", nil, err
	}
	env := os.Environ()
	env = append(env,
		"TRIM_CLI_CONFIG_DIR="+cfgDir,
		"TRIM_CLI_SESSION_STORAGE=file",
	)
	return cfgDir, env, nil
}

func sessionRoot() string {
	if p := os.Getenv("FNOS_SESSION_DIR"); p != "" {
		return p
	}
	exe, err := os.Executable()
	if err == nil {
		return filepath.Join(filepath.Dir(exe), ".session")
	}
	return ".session"
}

type ScanConfig struct {
	Host     string `json:"host"`
	Port     int    `json:"port"`
	Scheme   string `json:"scheme"`
	Insecure bool   `json:"insecure"`
	Username string `json:"username"`
	Password string `json:"password"`
	MainPort int    `json:"main_port"`
}

func (c *ScanConfig) normalize() error {
	host := strings.TrimSpace(c.Host)
	if host == "" {
		return errors.New("请填写 fnOS 地址")
	}
	if strings.Contains(host, "://") {
		u := strings.SplitN(host, "://", 2)[1]
		if i := strings.IndexByte(u, '/'); i >= 0 {
			u = u[:i]
		}
		host = u
		if h, p, err := net.SplitHostPort(u); err == nil {
			host = h
			if pn, e2 := strconv.Atoi(p); e2 == nil {
				c.Port = pn
			}
		}
	}
	c.Host = host
	if c.Scheme == "" {
		c.Scheme = "ws"
	}
	if c.MainPort == 0 {
		c.MainPort = defaultMainPort
	}
	if c.Port == 0 {
		c.Port = defaultMainPort
	}
	return nil
}

func (c *ScanConfig) baseArgs() []string {
	args := []string{
		"--host", c.Host,
		"--port", strconv.Itoa(c.Port),
		"--scheme", c.Scheme,
	}
	if c.Scheme == "ws" {
		args = append(args, "--allow-insecure-ws")
	} else if c.Insecure {
		args = append(args, "--tls-insecure")
	}
	return args
}

func runCli(ctx context.Context, env []string, args ...string) (string, error) {
	bin := cliPath()
	cctx, cancel := context.WithTimeout(ctx, cliTimeout)
	defer cancel()

	cmd := exec.CommandContext(cctx, bin, args...)
	cmd.Env = env
	out, err := cmd.CombinedOutput()
	if cctx.Err() == context.DeadlineExceeded {
		return "", fmt.Errorf("trim-cli 执行超时，请检查 NAS 地址与网络")
	}
	if err != nil {
		return "", fmt.Errorf("%s", strings.TrimSpace(string(out)))
	}
	return strings.TrimSpace(string(out)), nil
}

// ---------------------------------------------------------------------------
// 数据解析
// ---------------------------------------------------------------------------

type AppEntry struct {
	Name   string `json:"name"`
	AppID  string `json:"appName"`
	Port   int    `json:"port"`
	URL    string `json:"url"`
	Status string `json:"status"`
}

func parseApps(text string, mainPort int, host string) ([]AppEntry, error) {
	var root struct {
		List []map[string]any `json:"list"`
	}
	dec := json.NewDecoder(strings.NewReader(text))
	if err := dec.Decode(&root); err != nil {
		// 容错：可能是裸数组
		var arr []map[string]any
		if err2 := json.Unmarshal([]byte(text), &arr); err2 != nil {
			return nil, fmt.Errorf("解析应用列表失败: %v", err)
		}
		root.List = arr
	}

	results := []AppEntry{}
	for _, a := range root.List {
		si, _ := a["appServiceInfo"].(map[string]any)
		if si == nil {
			continue
		}
		urls, _ := si["urls"].(map[string]any)
		if urls == nil {
			continue
		}
		portStr, _ := urls["port"].(string)
		if portStr == "" {
			continue
		}
		pnum, err := strconv.Atoi(portStr)
		if err != nil || pnum == mainPort {
			continue
		}
		proto, _ := urls["protocol"].(string)
		if proto == "" {
			proto = "http"
		}
		path, _ := urls["path"].(string)
		if path == "" {
			path = "/"
		}
		name, _ := a["name"].(string)
		if name == "" {
			name, _ = a["appName"].(string)
		}
		appID, _ := a["appName"].(string)
		status, _ := a["status"].(string)
		results = append(results, AppEntry{
			Name:   name,
			AppID:  appID,
			Port:   pnum,
			URL:    fmt.Sprintf("%s://%s:%d%s", proto, host, pnum, path),
			Status: status,
		})
	}
	sort.Slice(results, func(i, j int) bool { return results[i].Port < results[j].Port })
	return results, nil
}

type DockerPort struct {
	Port  int    `json:"port"`
	Proto string `json:"proto"`
	IP    string `json:"ip"`
}

type DockerEntry struct {
	Name  string       `json:"name"`
	Image string       `json:"image"`
	State string       `json:"state"`
	Ports []DockerPort `json:"ports"`
}

func parseDocker(text string) ([]DockerEntry, error) {
	var list []map[string]any
	if err := json.Unmarshal([]byte(text), &list); err != nil {
		return nil, fmt.Errorf("解析容器列表失败: %v", err)
	}
	results := []DockerEntry{}
	for _, c := range list {
		names, _ := c["Names"].([]any)
		name := "?"
		if len(names) > 0 {
			if s, ok := names[0].(string); ok {
				name = strings.TrimPrefix(s, "/")
			}
		}
		image, _ := c["Image"].(string)
		state, _ := c["State"].(string)

		var pub []DockerPort
		if ports, ok := c["Ports"].([]any); ok {
			for _, item := range ports {
				p, ok := item.(map[string]any)
				if !ok {
					continue
				}
				if pp, ok := p["PublicPort"].(float64); ok && pp > 0 {
					proto, _ := p["Type"].(string)
					if proto == "" {
						proto = "tcp"
					}
					ip, _ := p["IP"].(string)
					if ip == "" {
						ip = "0.0.0.0"
					}
					pub = append(pub, DockerPort{
						Port:  int(pp),
						Proto: proto,
						IP:    ip,
					})
				}
			}
		}
		if len(pub) == 0 {
			continue
		}
		sort.Slice(pub, func(i, j int) bool { return pub[i].Port < pub[j].Port })
		results = append(results, DockerEntry{Name: name, Image: image, State: state, Ports: pub})
	}
	sort.Slice(results, func(i, j int) bool {
		return strings.ToLower(results[i].Name) < strings.ToLower(results[j].Name)
	})
	return results, nil
}

// ---------------------------------------------------------------------------
// 扫描
// ---------------------------------------------------------------------------

type ScanResult struct {
	OK       bool          `json:"ok"`
	Host     string        `json:"host"`
	MainPort int           `json:"mainPort"`
	Apps     []AppEntry    `json:"apps"`
	Docker   []DockerEntry `json:"docker"`
	Meta     struct {
		AppCount   int `json:"appCount"`
		DockerCnt  int `json:"dockerCount"`
		DockerPort int `json:"dockerPortTotal"`
	} `json:"meta"`
	ElapsedMs int64 `json:"elapsedMs"`
}

func scan(ctx context.Context, cfg ScanConfig) (*ScanResult, error) {
	start := time.Now()
	if err := cfg.normalize(); err != nil {
		return nil, err
	}
	_, env, err := sessionEnv(cfg.Host)
	if err != nil {
		return nil, fmt.Errorf("创建会话目录失败: %v", err)
	}

	// 登录（提供凭据时）
	if cfg.Username != "" && cfg.Password != "" {
		loginArgs := append(cfg.baseArgs(),
			"login", "-u", cfg.Username, "-p", cfg.Password)
		if out, err := runCli(ctx, env, loginArgs...); err != nil {
			msg := out
			if msg == "" {
				msg = err.Error()
			}
			return nil, fmt.Errorf("登录失败: %s", strings.TrimSpace(msg))
		}
	}

	// 应用列表 -> 非主端口入口
	appArgs := append(cfg.baseArgs(), "app", "list")
	appText, err := runCli(ctx, env, appArgs...)
	if err != nil {
		if sessionErrRe.MatchString(err.Error()) {
			return nil, errors.New("会话不可用或已过期，请重新提交用户名/密码")
		}
		return nil, fmt.Errorf("查询应用列表失败: %v", err)
	}
	apps, err := parseApps(appText, cfg.MainPort, cfg.Host)
	if err != nil {
		return nil, err
	}

	// Docker 容器 -> 宿主机映射端口
	dockerArgs := append(cfg.baseArgs(), "docker", "container", "ls")
	dockerText, err := runCli(ctx, env, dockerArgs...)
	if err != nil {
		if sessionErrRe.MatchString(err.Error()) {
			return nil, errors.New("会话不可用或已过期，请重新提交用户名/密码")
		}
		return nil, fmt.Errorf("查询容器列表失败: %v", err)
	}
	dockers, err := parseDocker(dockerText)
	if err != nil {
		return nil, err
	}

	res := &ScanResult{
		OK:       true,
		Host:     cfg.Host,
		MainPort: cfg.MainPort,
		Apps:     apps,
		Docker:   dockers,
	}
	for _, d := range dockers {
		res.Meta.DockerCnt++
		res.Meta.DockerPort += len(d.Ports)
	}
	res.Meta.AppCount = len(apps)
	res.ElapsedMs = time.Since(start).Milliseconds()
	return res, nil
}

// ---------------------------------------------------------------------------
// HTTP
// ---------------------------------------------------------------------------

func writeJSON(w http.ResponseWriter, code int, obj any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(obj)
}

func handleScan(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"ok": false, "error": "method not allowed"})
		return
	}
	var cfg ScanConfig
	if err := json.NewDecoder(r.Body).Decode(&cfg); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "请求格式错误: " + err.Error()})
		return
	}
	res, err := scan(r.Context(), cfg)
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	storeResult(res)
	writeJSON(w, http.StatusOK, res)
}

// ---------------------------------------------------------------------------
// 端口清单导出（/ports.json，供「飞牛端口映射」App 拉取）与二维码
// ---------------------------------------------------------------------------

var (
	resultMu sync.Mutex
	lastRes  *ScanResult
	lastAt   time.Time
)

func storeResult(r *ScanResult) {
	resultMu.Lock()
	defer resultMu.Unlock()
	lastRes = r
	lastAt = time.Now()
}

func loadResult() (*ScanResult, time.Time) {
	resultMu.Lock()
	defer resultMu.Unlock()
	return lastRes, lastAt
}

// PortsPayload 是导出给手机 App 的端口清单格式。
type PortsPayload struct {
	OK          bool          `json:"ok"`
	Host        string        `json:"host"`
	MainPort    int           `json:"mainPort"`
	Ports       []int         `json:"ports"`
	Apps        []AppEntry    `json:"apps"`
	Docker      []DockerEntry `json:"docker"`
	GeneratedAt string        `json:"generatedAt"`
}

// buildPortsPayload 汇总主端口 + 应用入口端口 + Docker 宿主端口（去重、升序）。
func buildPortsPayload(res *ScanResult, at time.Time) PortsPayload {
	seen := map[int]bool{}
	ports := []int{}
	add := func(p int) {
		if p > 0 && p <= 65535 && !seen[p] {
			seen[p] = true
			ports = append(ports, p)
		}
	}
	add(res.MainPort)
	for _, a := range res.Apps {
		add(a.Port)
	}
	for _, d := range res.Docker {
		for _, p := range d.Ports {
			add(p.Port)
		}
	}
	sort.Ints(ports)
	return PortsPayload{
		OK:          true,
		Host:        res.Host,
		MainPort:    res.MainPort,
		Ports:       ports,
		Apps:        res.Apps,
		Docker:      res.Docker,
		GeneratedAt: at.Format(time.RFC3339),
	}
}

func handlePortsJSON(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"ok": false, "error": "method not allowed"})
		return
	}
	res, at := loadResult()
	if res == nil {
		writeJSON(w, http.StatusNotFound, map[string]any{
			"ok":    false,
			"error": "尚无扫描结果，请先在网页上完成一次扫描",
		})
		return
	}
	writeJSON(w, http.StatusOK, buildPortsPayload(res, at))
}

// handleQR 返回当前访问地址 /ports.json 的二维码 PNG，供手机 App 扫码填入在线地址。
func handleQR(w http.ResponseWriter, r *http.Request) {
	scheme := "http"
	if r.TLS != nil {
		scheme = "https"
	}
	if p := r.Header.Get("X-Forwarded-Proto"); p != "" {
		scheme = p
	}
	target := scheme + "://" + r.Host + "/ports.json"
	png, err := qrcode.Encode(target, qrcode.Medium, 360)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "image/png")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write(png)
}

func main() {
	addr := ":8787"
	if v := os.Getenv("FNOS_LISTEN"); v != "" {
		addr = v
	}
	if len(os.Args) > 1 && os.Args[1] == "--listen" && len(os.Args) > 2 {
		addr = os.Args[2]
	}

	mux := http.NewServeMux()

	// 嵌入的前端静态资源
	sub, err := fs.Sub(staticFS, "static")
	if err != nil {
		log.Fatalf("嵌入资源失败: %v", err)
	}
	mux.Handle("/static/", http.StripPrefix("/static/", http.FileServer(http.FS(sub))))
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		data, _ := fs.ReadFile(sub, "index.html")
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(data)
	})
	mux.HandleFunc("/api/health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{
			"ok":  true,
			"cli": cliPath(),
		})
	})
	mux.HandleFunc("/api/scan", handleScan)
	mux.HandleFunc("/ports.json", handlePortsJSON)
	mux.HandleFunc("/api/ports", handlePortsJSON)
	mux.HandleFunc("/api/qr", handleQR)

	ln, err := net.Listen("tcp", addr)
	if err != nil {
		log.Fatalf("监听 %s 失败: %v", addr, err)
	}
	hostPort := ln.Addr().String()

	fmt.Println("========================================================")
	fmt.Println("  fnOS 端口扫描器 (Go) 已启动")
	fmt.Printf("  浏览器访问:  http://127.0.0.1%s\n", hostPort[strings.LastIndex(hostPort, ":"):])
	fmt.Println("  trim-cli:   ", cliPath())
	fmt.Println("  按 Ctrl+C 停止")
	fmt.Println("========================================================")

	srv := &http.Server{Handler: mux, ReadHeaderTimeout: 10 * time.Second}
	if err := srv.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatalf("服务异常退出: %v", err)
	}
}
