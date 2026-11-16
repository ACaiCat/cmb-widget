# 招行账单小组件

<img src="app/src/main/artwork/app-icon.svg" width="80" alt="招行账单小组件图标" />

[下载 Release 安装包](https://github.com/ACaiCat/cmb-widget/releases/latest)

通过 IMAP 读取招行账户变动邮件，自动提取、保存账单，在桌面查看每日支出、月度支出和交易明细。

## 使用

1. 开启邮箱 IMAP，在 APP 中填写服务器、账号和授权码。
2. 长按桌面，添加「今日支出」或「今日账单」小组件。
3. 打开 APP 或点击组件即可同步。退款抵扣支出，普通收入单独记录。

## 构建

```shell
./gradlew :app:assembleRelease
```

Release 使用 R8 压缩代码和资源。签名通过环境变量 `CMB_KEYSTORE_FILE`、`CMB_KEYSTORE_PASSWORD`、`CMB_KEY_ALIAS`、`CMB_KEY_PASSWORD` 配置。
