# BoardLive

---

**免责提醒**
**本项目由AI（DeepSeek）开发，仅提供BUG解决及功能开发，不提供技术支持，如果觉得好用的话，请给我点一个Star**

---

**实时在线人数 Board.xaml 分发服务** —— 一个 Java 17 独立应用(不是 MC 服务端插件,零第三方依赖)。

它做四件事:

| 功能 | 类 | 说明 |
| --- | --- | --- |
| ① 读取人数 | `getServerPlayerCount` | 从 `config.yml` 读取 MC 服务器地址,通过 Minecraft 服务器列表查询协议拿到在线人数(Java 版 / 基岩版都支持) |
| ② 解析模板 | `getBoardConfig` | 读取 `Board.xaml`,识别其中的 `%player_count%` 变量 |
| ③ 内存替换 + 下载 | `LiveBoardService` | 在内存里把 `%player_count%` 替换成真实数字,按 `config.yml` 里配置的端口提供 `Board.xaml` 下载 |
| ④ 智能更新 | `LiveBoardService` + `PlayerCountCache` | 每收到一次下载请求就重新执行 ①→③,下载到的永远是最新人数 |

---

## 1. 环境要求

- **JDK 17 或更高版本**(编译与运行都要求)
- 无需 Maven / Gradle / 任何第三方库;jar 只有 ~46 KB
- 本机需要能访问目标 MC 服务器的查询端口(Java 版通常是 TCP 25565,基岩版是 UDP 19132)

## 2. 构建

```powershell
# Windows
.\build.bat
# 或
powershell -ExecutionPolicy Bypass -File .\build.ps1

# Linux / macOS
chmod +x build.sh && ./build.sh
```

产物在 `dist/`:`BoardLive.jar` + `config.yml` + `Board.xaml`。

也可以直接用 Maven(没有任何依赖需要下载,只需要 Maven 插件):

```bash
mvn -q package        # 产物 target/BoardLive.jar
```

## 3. 快速开始

把 `BoardLive.jar` 放到任意一个目录,双击/命令行运行即可:

```bash
java -jar BoardLive.jar
```

启动流程是全自动的:

1. 程序会在 **jar 所在目录** 找 `config.yml`,找不到就生成一份带注释的默认配置;
2. 同样会检查 `Board.xaml`(找不到就生成示例模板);
3. 直接按配置开始提供服务,不需要任何子命令。

首次启动后,把 `config.yml` 里的 `server.address` 改成你的服务器地址(例如 `play.example.com:25565`),重新运行即可:

```bash
java -jar BoardLive.jar
# 另一种:临时指定端口
java -jar BoardLive.jar -p 9000
```

```bash
# 下载(每次下载都会实时查询人数)
curl -o Board.xaml http://127.0.0.1:8080/Board.xaml
```

启动后控制台会打印:

```
[INFO] BoardLive 1.1.0 已启动(独立应用,无需 MC 服务端插件)
[INFO] 程序目录   : D:\board
[INFO] 配置文件   : D:\board\config.yml
[INFO] 目标服务器 : play.example.com:25565 (auto)
[INFO] Board 模板 : D:\board\Board.xaml (auto)
[INFO] 下载地址   : http://127.0.0.1:8080/Board.xaml
[INFO] 状态页面   : http://127.0.0.1:8080/
[INFO] 当前人数   : 42 / 100
```

> 默认读取位置是 **jar 同目录**,与你从哪个目录执行 `java -jar` 无关。
> 想用别的配置文件:`java -jar BoardLive.jar -c D:\other\config.yml`。

浏览器打开 `http://127.0.0.1:8080/` 可以看到实时人数面板(每 3 秒自动刷新),并直接下载 Board.xaml。

## 4. 启动参数

没有子命令,`java -jar BoardLive.jar` 就是启动服务。

```
java -jar BoardLive.jar [参数]

  -p, --port <端口>     覆盖配置文件里的下载端口(例如 -p 9000)
  -c, --config <文件>   指定配置文件(默认:jar 同目录的 config.yml)
      --debug           输出调试日志
      --no-color        关闭彩色日志
  -h, --help            显示帮助
  -v, --version         显示版本
```

示例:

```bash
java -jar BoardLive.jar                 # 默认 8080
java -jar BoardLive.jar -p 9000         # 临时换端口
java -jar BoardLive.jar --debug         # 排查问题
```

## 5. 配置 `config.yml`

```yaml
server:
  address: "127.0.0.1:25565"   # 目标服务器,支持 host:port;[::1]:port 也行
  host: "play.example.com"     # 可选,和 port 一起写会覆盖 address
  port: 25565
  edition: "auto"              # java / bedrock / auto
  timeout-ms: 3000             # 连接超时
  retries: 1                   # 失败重试次数
  protocol-version: 47         # Java 版列表查询协议号,一般不用改

http:
  bind: "0.0.0.0"              # 监听地址
  port: 8080                   # 下载端口
  path: "/Board.xaml"          # 下载路径(大小写不敏感)
  download-name: "Board.xaml"  # 浏览器下载时的文件名
  threads: 8                   # 工作线程数

board:
  file: "Board.xaml"           # 模板文件,相对本配置文件所在目录
  charset: "auto"              # auto / UTF-8 / GBK / UTF-16 ...
  placeholder: "%player_count%"
  reload-on-change: true       # 模板改了自动重新加载,不用重启
  extras: true                 # 是否额外替换 %player_max% %motd% 等

refresh:
  on-request: true             # 每次下载都重新查询(实时)
  cache-ms: 0                  # 结果缓存毫秒数,0 = 不缓存
  dedupe-ms: 200               # 并发去重窗口,防止被刷接口时打爆目标服务器
  on-error: "last-known"       # 查询失败时: zero / last-known / keep-placeholder / fail
  fallback-player-count: 0

logging:
  debug: false
```

### 查询失败时的四种行为

| `on-error` | 下载到的内容 |
| --- | --- |
| `zero` | 人数按 `fallback-player-count`(默认 0)替换 |
| `last-known`(默认) | 用最近一次成功的人数,状态头 `X-Board-State: stale` |
| `keep-placeholder` | 原样返回模板,`%player_count%` 保留 |
| `fail` | 返回 HTTP 503,body 为错误原因 |

### Board.xaml 支持的可替换变量

| 变量 | 含义 |
| --- | --- |
| `%player_count%` | 当前在线人数(核心) |
| `%player_max%` | 最大人数 |
| `%motd%` | 服务器 MOTD(已转义 `& < >`,换行压成空格) |
| `%version%` | 服务器版本 |
| `%ping_ms%` | 本次查询耗时 |
| `%server_edition%` | `java` / `bedrock` |

未定义的 `%xxx%` 会**原样保留**,不会被清空。`board.extras: false` 时只替换 `%player_count%`。

> <!-- --> 注释里的变量**不会**被替换,所以可以放心在模板开头写变量说明。

## 6. HTTP 接口

| 路径 | 说明 |
| --- | --- |
| `/Board.xaml`(可配置) | 下载渲染后的文件,`Content-Disposition: attachment` |
| `/board.xaml`、`/download`、`/board` | 同上(固定别名) |
| `/download/inline` | 同上,但不带 attachment(浏览器内直接预览) |
| `/status` | JSON 状态:`ok/online/max/motd/version/edition/latencyMs/error/...` |
| `/health` | 存活检查,返回 `ok` |
| `/` | 实时状态页(带下载按钮) |

下载响应头示例(方便监控/排查):

```
Content-Type: application/xaml+xml; charset=utf-8
Content-Disposition: attachment; filename="Board.xaml"; filename*=UTF-8''Board.xaml
Cache-Control: no-store, no-cache, must-revalidate, max-age=0
X-Player-Count: 42
X-Player-Max: 100
X-Board-State: live        # live / cached / stale / fallback / raw
X-Ping-Ms: 12
X-Board-Generated: 2026-10-18T20:54:32.123Z
```

## 7. 工作原理

**① 读取人数** 用的是 Minecraft 官方服务器列表查询协议,不需要开启 `enable-query`,也不需要任何插件:

- Java 版:TCP handshake + status(`0x00`),解析 `players.online`;失败自动退化到 1.6 的 legacy `0xFE` 查询
- 基岩版:UDP RakNet `Unconnected Ping(0x01)` → `Unconnected Pong(0x1C)`,取 `MCPD;...` 字段里的在线人数
- `edition: auto`:先试 Java 版(连不上会立刻失败),再试基岩版

**② 解析模板** 把 Board.xaml 当作文本读取(自动识别 UTF-8 / UTF-8 BOM / UTF-16 / GBK),统计并替换 `%player_count%`;渲染结果沿用源文件编码,所以 GBK 模板下发的仍然是 GBK 字节。

**③④ 实时更新** 每个下载请求进入 `PlayerCountCache`:

1. 缓存窗口(`max(cache-ms, dedupe-ms)`)内的并发请求复用同一次查询 → 即使被高频拉取,也不会把目标服务器打爆;
2. 窗口外的请求现场查询,拿到最新人数;
3. 用最新结果重新渲染模板(模板文件变了会自动重载),再返回给客户端。

也就是说:**客户端每下载一次,数字就是那一刻的在线人数**。

## 8. 部署建议

### Windows

```bat
:: 直接后台运行
start "" javaw -jar D:\board\dist\BoardLive.jar
```

用 [NSSM](https://nssm.cc/) 注册成服务:

```bat
nssm install BoardLive "C:\Program Files\Java\jdk-17\bin\java.exe" "-jar D:\board\dist\BoardLive.jar"
nssm start BoardLive
```

### Linux

```ini
# /etc/systemd/system/boardlive.service
[Unit]
Description=BoardLive
After=network.target

[Service]
WorkingDirectory=/opt/boardlive
ExecStart=/usr/bin/java -jar /opt/boardlive/BoardLive.jar
Restart=always
User=nobody

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable --now boardlive
```

### 反向代理

如果前面有 Nginx,记得关闭缓存,否则"实时"就失效了:

```nginx
location /Board.xaml {
    proxy_pass http://127.0.0.1:8080;
    proxy_cache off;
    add_header Cache-Control "no-store" always;
}
```

## 9. 常见问题

**Q: 启动后提示查询失败(`Connection refused` / 超时)?**
确认 `server.address` 的 IP 与端口能被本机访问;Java 版默认 25565(TCP),基岩版默认 19132(UDP);服务器开启了白名单/防火墙/防压测插件时,列表查询可能被拦截。基岩版请把 `edition` 设为 `bedrock` 或 `auto`。

**Q: 目标服务器套了 BungeeCord / Velocity / Waterfall?**
直接指向代理的地址与端口即可,查询协议由代理转发,人数是**全服**在线人数。

**Q: Java 版和基岩版是同一个服务器(Geyser)?**
把 `edition` 设为 `auto`:会优先用 Java 版协议,失败后自动尝试基岩版 UDP。

**Q: `%player_count%` 没被替换?**
检查 `board.file` 是否指向正确文件、`board.placeholder` 是否和模板里写的完全一致(含 `%`),以及启动日志里的"Board 模板"路径是否指向了正确的文件。

**Q: 模板是 GBK 编码,下载后乱码?**
`board.charset: auto` 会自动识别 UTF-8/UTF-16/GBK;若识别不准,直接写死 `charset: "GBK"`。程序按源文件编码输出,不会强行转成 UTF-8。

**Q: 控制台中文乱码?**
Windows 控制台建议用 `chcp 936`(默认)即可。若你的终端是 UTF-8,可加 JVM 参数:`java -Dfile.encoding=UTF-8 -jar ...`。

**Q: 端口被占用?**
改 `http.port`,或临时用 `-p 9000`。

**Q: 担心被刷接口拖垮 MC 服务器?**
`refresh.dedupe-ms` 控制并发复用窗口(默认 200ms),`refresh.cache-ms` 可以进一步缓存;两者都不会影响单次下载的实时性。

## 10. 作为库调用

四个功能都是普通类,可以直接在自己的 Java 17 项目里用:

```java
import mcboard.*;

Config config = Config.load(new File("config.yml"));

// 功能一:读人数
getServerPlayerCount counter = new getServerPlayerCount(config);
PlayerCountResult result = counter.fetch();
int online = result.getOnline();

// 功能二 + 三:读模板并替换
getBoardConfig board = new getBoardConfig(config.getBoardFile());
String rendered = board.render(result);
System.out.println(board.countPlaceholders() + " 处占位符 -> " + online + " 人");

// 功能三:自己起服务(可选,端口来自 config.yml)
LiveBoardService service = new LiveBoardService(config);
service.start();
```

## 11. 目录结构

```
mc-board-live/
├── build.ps1 / build.bat / build.sh   构建脚本(JDK 17)
├── pom.xml                            可选:用 Maven 构建
├── config.yml                         默认配置
├── Board.xaml                         示例模板(请替换成你自己的)
├── dist/                              构建产物:BoardLive.jar(+ 首启自动生成的 config.yml / Board.xaml)
├── src/main/java/mcboard/
│   ├── Main.java                      程序入口(自动生成配置 + 启动服务)
│   ├── getServerPlayerCount.java      功能一
│   ├── getBoardConfig.java            功能二
│   ├── LiveBoardService.java          功能三 / 四(HTTP 服务)
│   ├── PlayerCountCache.java          实时刷新与并发去重
│   ├── PlayerCountResult.java         查询结果值对象
│   ├── JavaStatusPinger.java          Java 版查询协议
│   ├── BedrockPinger.java             基岩版查询协议
│   ├── Config.java                    config.yml 解析
│   ├── SimpleYaml.java                零依赖 YAML 读取
│   ├── Json.java                      零依赖 JSON 解析
│   └── Log.java                       控制台日志
└── tools/mock/MockMcServer.java       自测用 Mock 服务器(非交付物)
```

## 12. 自测(可选)

仓库内置了一个 Mock MC 服务器(同时模拟 Java 版 TCP 与基岩版 UDP,人数从文件实时读取):

```bash
# 编译 Mock
javac -d build/mock tools/mock/MockMcServer.java

# 启动 Mock(Java 25601 / 基岩 19133 / 人数来自 mock-count.txt)
echo 42 > mock-count.txt
java -cp build/mock MockMcServer 25601 19133 mock-count.txt

# 另起终端:准备一份指向 Mock 的配置(server.address: "127.0.0.1:25601")
java -jar dist/BoardLive.jar -c test-config.yml

# 改 mock-count.txt 后重新下载,响应头里的 X-Player-Count 会跟着变
curl -s -D - -o body.xaml http://127.0.0.1:8080/Board.xaml | findstr /i x-player-count
```
