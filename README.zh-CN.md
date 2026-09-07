# Keycloak 腾讯云验证码认证器

[English](README.md)

这是一个非官方 Keycloak 认证器，以独立的 `REQUIRED` Browser Flow execution 方式加入腾讯云验证码。项目只交付一个轻量 provider JAR，不依赖特定应用、密钥系统、部署平台或第三方登录主题。

可选的 [Sentry 错误上报](docs/configuration.md#optional-sentry-diagnostics) 可记录验证码服务端失败，包括腾讯云 API 密钥失效。在仓库根目录的 `.env` 填写 `SENTRY_DSN` 后运行 `task build`（或 `./mvnw -B verify`）即可接入，无需在代码中硬编码；运行时同名环境变量可以覆盖构建配置。上报不包含验证码票据、密码、IP 或密钥。

This is not an official Tencent, Tencent Cloud, or Keycloak project（本项目不是腾讯、腾讯云或 Keycloak 官方项目）。腾讯及腾讯云的名称和标志归腾讯所有；Keycloak 的名称和标志归各自权利人所有。

## 兼容性与发行标识

| 扩展版本 | Keycloak | Java | 状态 |
| --- | --- | --- | --- |
| `0.1.x` | `26.7.0` | `21` | 已支持并通过验收测试的基线 |
| 其他版本 | 其他版本 | 其他版本 | 在同一容器验收套件通过前不作兼容承诺 |

Maven 坐标：`io.github.invoker-bot:keycloak-tencent-captcha-authenticator:0.1.0`<br>
Java 包：`io.github.invokerbot.keycloak.tencentcaptcha`<br>
Provider ID：`tencent-captcha`

Maven group 中的 `invoker-bot` 有连字符，Java 包名没有。参见 [`0.1.x` 兼容性契约](docs/compatibility.md)。

受支持基线是 Keycloak 26.7.0 on Java 21。

## 安装前准备

1. 在腾讯云验证码控制台创建或选择 Web/App 验证应用，取得 `CaptchaAppId` 和 `AppSecretKey`。
2. 为服务端校验创建腾讯云 API 身份。在账户的验证码授权模型允许时，仅授予 CAM action `captcha:DescribeCaptchaResult`，不要使用宽泛的管理员凭据。请核对腾讯云 [CAM 策略语法](https://cloud.tencent.com/document/product/598/10603) 和账户对应的验证码产品授权文档。
3. 确认浏览器能解析并访问 `turing.captcha.qcloud.com` 与仅用于脚本的 origin `turing.captcha.gtimg.com`，并确认 Keycloak 服务端能解析并访问 `captcha.tencentcloudapi.com`。腾讯文档说明这些域名使用动态 IP，因此应按域名放行，不要固定 IP。
4. 正确配置 Keycloak proxy headers 与 trusted proxy（受信代理）地址。Provider 把 Keycloak connection context 暴露的地址作为 `UserIp` 发送；若信任代理配置错误，腾讯收到的可能是反向代理 IP，而非浏览器 client IP。

腾讯官方参考：[TJCaptcha/Web 接入](https://cloud.tencent.com/document/product/1110/36828)、[`DescribeCaptchaResult`](https://cloud.tencent.com/document/api/1110/36926)、[`aidEncrypted` 鉴权](https://cloud.tencent.com/document/product/1110/128489)。

## 构建

使用 Java 21 或更高版本和 [Task v3](https://taskfile.dev/docs/installation) 构建 Java 21 产物：

```bash
task build
```

该命令执行 `./mvnw -B verify`，包含 Java 测试和格式检查。发行 JAR 为 `target/keycloak-tencent-captcha-authenticator-0.1.0.jar`。

| 命令 | 用途 |
| --- | --- |
| `task test` | 运行 Java、浏览器脚本和 Python 单元测试 |
| `task verify` | 构建 provider 并运行全部本地检查 |
| `task test:integration` | 在 Linux 和 Docker Compose 环境中构建并运行两组容器验收 |
| `task artifacts` | 完成验证，将 JAR、校验和和 SBOM 生成到已忽略的 `dist/` |
| `task --list` | 查看全部任务，包括单独测试、格式化和清理命令 |

打包版本号自动读取 `pom.xml`。环境要求和底层命令见 [Contributing](CONTRIBUTING.md)。构建仍会读取 `.env` 中的 Sentry 配置；生成公开分发的产物时应使用不含该本地文件的工作目录。

## JAR 安装

停止 Keycloak，将发行 JAR 复制到 provider 目录，重建优化镜像后再启动：

```bash
install -m 0644 target/keycloak-tencent-captcha-authenticator-0.1.0.jar \
  /opt/keycloak/providers/keycloak-tencent-captcha-authenticator-0.1.0.jar
/opt/keycloak/bin/kc.sh build
/opt/keycloak/bin/kc.sh start --optimized
```

在优化模式的 Keycloak 中，加入、替换、回滚或移除 provider 后都必须执行 `kc.sh build`。依据官方 [Keycloak Server Developer Guide](https://www.keycloak.org/docs/latest/server_development/index.html) 和 [provider 配置指南](https://www.keycloak.org/server/configuration-provider)。

## 密钥文件

默认路径为 `/run/secrets/tencent-captcha.env`。精确 schema 是四行非空、以 LF 分隔的 UTF-8 赋值，不允许未知 key 或重复 key：

```dotenv
CAPTCHA_APP_ID=<CAPTCHA_APP_ID>
CAPTCHA_APP_SECRET_KEY=<CAPTCHA_APP_SECRET_KEY>
TENCENT_SECRET_ID=<TENCENT_SECRET_ID>
TENCENT_SECRET_KEY=<TENCENT_SECRET_KEY>
```

`CAPTCHA_APP_ID` 是公开的浏览器标识；另外三个值是服务端 secret。四个值应放在同一个受限文件中，以便原子替换。文件必须：

- 是 regular（普通）且非 symlink（符号链接）的文件，大小最多 65,536 字节；
- mode 只能是 `0400` 或 `0600`，并能被 Keycloak 运行用户读取；
- 直接父目录必须是真实目录而非 symlink，且 group/other 不可写（推荐 `0700`）；
- 使用有效 UTF-8 与 LF 换行，且只包含上述四个 key。

JVM system property `KC_SPI_TENCENT_CAPTCHA_SECRET_FILE` 的优先级高于同名 environment variable；空值继续回退，最后使用默认路径。这个 setting 只能存路径。Secret 值不支持放入 environment variables、命令行参数、Realm attributes、Authenticator Config、Realm exports、provider JAR 或镜像层。

详见[配置与轮换](docs/configuration.md)。

## Docker Compose 安装

Linux 示例使用只读 host bind mount，因为 provider 会校验 POSIX ownership 和 mode。Docker Desktop 与非 POSIX 主机不能保证等价的主机文件元数据。

```bash
./mvnw -B verify
sudo examples/docker-compose/prepare-secret.sh /secure/input/tencent-captcha.env
stat -c '%u:%g:%a' examples/docker-compose/secrets examples/docker-compose/secrets/tencent-captcha.env
docker compose -f examples/docker-compose/compose.yaml up --build --detach
```

维护者可在 Linux + Docker 上先运行 `./mvnw -B verify`，再执行 `python3 tests/integration/run_public_example.py`，端到端检验这一公开示例。只要 `examples/docker-compose/secrets/tencent-captcha.env` 以任何形式存在，该有界 runner 就会在启动前拒绝执行；否则它会使用公开 secret helper 与合成值，校验 `700`/`1000:0:400` 元数据，等待服务就绪，并只清理自己生成的 secret、容器、volume 与 orphan。

预期目录 mode 为 `700`；文件 owner/mode 为 `1000:0:400`。UID `1000` 是示例固定的 `quay.io/keycloak/keycloak:26.7.0` 镜像运行用户。使用其他镜像时，必须替换成经确认的 runtime UID。Helper 仅支持 Linux，需要 root 设置 ownership，会在不跟随 symlink 的前提下校验来源，并原子安装目标文件。示例只绑定 `127.0.0.1`；生产 hostname、TLS、proxy headers 和管理员凭据需另行配置。

示例有意不通过 Compose interpolation、`env_file` 或 top-level Compose secrets 传入凭据值。参见 Docker 官方[密钥指南](https://docs.docker.com/compose/how-tos/use-secrets/)与 [Compose trust model](https://docs.docker.com/compose/trust-model/)。

## 配置 Browser Flow

在每个需要保护的 realm 的 Keycloak Admin Console 中：

1. 打开 **Authentication > Flows**。
2. 复制内置 **Browser** flow；不要直接修改内置 flow。
3. 在副本中打开 **Forms** subflow。
4. 添加 **Tencent CAPTCHA** execution（`tencent-captcha`）。
5. 设置为 `REQUIRED`，并放在 **Username Password Form** 的正前方。
6. 将该副本绑定为 realm 的 Browser Flow；上线前用非管理员账户验证。

内置 **SSO Cookie** execution 位于 Forms 之前，因此已有有效 SSO cookie 的会话会绕过新的验证码。这是有意行为。Registration（注册）、password reset（密码重置）、action-token、broker 和 direct grant 并不会自动受到保护；如这些入口也需要验证码，应分别设计控制。Direct grant 没有浏览器页面，不能使用此 execution。

简言之：registration、reset 与 direct grant 均不在这个 Browser Flow 的自动覆盖范围内。

详见[认证流程指南](docs/authentication-flow.md)。

## 运行与安全行为

- 浏览器加载精确的入口脚本 `https://turing.captcha.qcloud.com/TJCaptcha.js`；该 provider script 可能继续从精确 origin `https://turing.captcha.gtimg.com` 加载动态脚本。Challenge 只把 `ticket` 与 `randstr` 提交到 Keycloak 精确的 `url.loginAction`。
- 服务端把 `Ticket`、`Randstr`、`UserIp`、`CaptchaAppId`、`AppSecretKey` 和固定的 `CaptchaType=9` 发往 `https://captcha.tencentcloudapi.com`，action 为 `DescribeCaptchaResult`，API version 为 `2019-07-22`。
- `TENCENT_SECRET_ID` 会在 TC3 `Authorization` header 中作为 `Credential=` 标识发送，后面带 credential scope。`TENCENT_SECRET_KEY` 只用于本地 HMAC 密钥派生与请求签名链，不会传输给腾讯。
- 只有整数 `CaptchaCode == 1` 才成功。配置缺失、输入无效、`trerror_*`、timeout、传输错误、非 2xx、API 错误、畸形响应及其他 code 一律 fail-closed（关闭式失败）。没有 fail-open 选项，也不会自动重试 proof。浏览器会在提交前拒绝 `trerror_*` disaster ticket；可选诊断只包含 `category: "disaster-ticket"` 与整数 `errorCode`（否则为 `null`），绝不包含 `ticket`、`randstr` 或 `errorMessage`。
- 仅在 CAPTCHA response 上，provider 保留 realm browser security headers，并给 `script-src` 增加新 nonce。`https://turing.captcha.qcloud.com` 会加入 `script-src`、`frame-src` 与 `connect-src`，而仅用于脚本的 origin `https://turing.captcha.gtimg.com` 只会加入 `script-src`。若原策略没有 `worker-src`，派生的 challenge 策略会加入 `worker-src 'self' blob:`；若已存在，则保留其来源并只追加 `'self'` 与 `blob:`，让腾讯 challenge 能创建 blob-backed worker。当 realm 同时未定义 `script-src` 与 `default-src` 时，派生策略还会加入 `'self'`，保证同源登录主题 module 可加载；显式的 `script-src` 或 `default-src` 仍保持权威。若策略包含 `*`、`'unsafe-inline'`、`'unsafe-eval'` 或重复 directive，则拒绝 challenge，不会弱化策略。不会修改 realm-wide CSP。

Privacy（隐私）披露：用户浏览器会加载并执行腾讯托管的代码；Keycloak 服务端会把 CAPTCHA proof fields 和 client IP 发给腾讯进行校验。启用前应评估腾讯云条款、隐私文档、留存与区域处理规则，并更新自己的用户隐私声明。

完整威胁边界、日志规则、精确 CSP 行为和网络目的地见[安全模型](docs/security-model.md)。

## Upgrade、rollback 与 uninstall

以下 upgrade、rollback、uninstall 与 troubleshooting 步骤构成运维 runbook。

任何生命周期变更前，先按已经验证的备份流程导出 Keycloak 配置，保留上一版已验证 JAR，并在 staging 中测试 copied flow。

- **Upgrade（升级）：**停止 Keycloak；用新版已验证 JAR 替换旧版；执行 `/opt/keycloak/bin/kc.sh build`；使用 `--optimized` 启动；确认 provider discovery、CSP 与受保护登录。
- **Rollback（回滚）：**停止 Keycloak；恢复上一版 JAR；执行 `/opt/keycloak/bin/kc.sh build`；重启并重复 smoke checks。不要混装多个版本。
- **Uninstall（卸载）：**按以下顺序执行，避免仍在运行的 flow 或容器突然失去必需的 provider/secret：
  1. 先绑定不引用 `tencent-captcha` 的 Browser Flow（或移除 execution），验证一次全新登录，并确认所有 realm binding 都不再引用该 execution。
  2. 停止 Keycloak workload。若使用仓库提供的 Docker Compose 示例，必须先停止并移除示例容器与网络，再处理 bind-mounted 文件：`docker compose -f examples/docker-compose/compose.yaml down --remove-orphans`。
  3. 删除受限 runtime secret 与 provider JAR，并从已部署的 service definition 移除 secret-file path setting 与 bind-mount 配置。容器仍在运行时绝不能删除 mounted secret。
  4. 对仍需继续运行的 distribution 安装执行 `/opt/keycloak/bin/kc.sh build`，在不含 provider/path/mount 配置的状态下重启，并验证普通登录与管理功能。
  5. 只有在所有使用这些凭据的部署均已停止或迁移后，才吊销专用腾讯云 API 身份；还需确认 CAPTCHA 应用 key 没有其他 consumer，才能停用它。

保留指向已删除 provider 的 flow 引用可能破坏认证管理。在解除受保护 flow 或停止 workload 之前吊销腾讯云身份，会造成可避免的 fail-closed 登录中断。

## Troubleshooting（故障排查）

- **Keycloak 正常启动，但受保护登录返回 service unavailable：**检查最终解析的 secret path、regular file 与父目录元数据、ownership、精确 schema、UTF-8/LF 编码及 `0400`/`0600` mode。Secret 是 lazy load，因此无关 realm 可继续 ready。
- **Admin Console 中没有 provider：**确认 JAR 位于 `/opt/keycloak/providers/`，重新执行 `kc.sh build`，检查启动日志中的 provider loading 或 split-package 错误。
- **CAPTCHA script 或 frame 被阻止：**在出站/浏览器网络策略中只放行精确 Tencent origin。不要加入通配 CSP、`'unsafe-inline'` 或 `'unsafe-eval'`。
- **所有 proof 都被拒绝：**确认 CAPTCHA 应用 ID/key pair、`DescribeCaptchaResult` CAM 权限、服务端时间、腾讯 API 的 HTTPS 连通性和 trusted proxy 配置。不要记录 proof 或 credential 值。
- **腾讯收到代理 IP：**按 Keycloak 支持的方式配置 proxy-header，并把 trusted proxies 限定到真实代理地址；绝不能信任任意客户端提交的 forwarding headers。

## 项目文档

- [Configuration / 配置](docs/configuration.md)
- [Authentication flow / 认证流程](docs/authentication-flow.md)
- [Security model / 安全模型](docs/security-model.md)
- [Compatibility / 兼容性](docs/compatibility.md)
- [Security reporting / 安全报告](SECURITY.md)
- [Contributing / 贡献](CONTRIBUTING.md)
- [Changelog](CHANGELOG.md)

项目采用 Apache-2.0 许可。贡献按仓库 Apache License 2.0 条款提交。
