# 验证

项目根目录执行：

```shell
./gradlew :app:assembleDebug :app:lintDebug
```

解析、统计和同步中断检查：

```shell
./gradlew :app:testDebugUnitTest
```

`ParserChecks` 检查交易模板、日期和 MIME。设置 `CMB_TEST_FIXTURES` 后也会检查指定的样例文件，每行是日期和 Base64 编码的 UTF-8 邮件正文，以制表符分隔。

`MailboxChecks` 在设置 `CMB_TEST_USER` 和 `CMB_TEST_PASSWORD` 后执行只读邮箱测试，覆盖所有文件夹和批量读取。凭据通过环境变量传入。

`BillMathChecks` 检查退款抵扣、收入和币种隔离以及金额正负号。

`SyncControlTest` 覆盖取消前后建立连接、阻止已取消任务连接 IMAP、解除阻塞的 socket 读取，以及连接与取消并发时的资源释放。

`BillExclusionTest` 检查排除支出、退款和收入后的合计，以及恢复统计和币种隔离。

数据库升级和详情开关的真机测试使用独立包名，避免改动正式账本：

```shell
./gradlew :app:connectedDebugAndroidTest -PisolatedTests=true -Pandroid.testInstrumentationRunnerArguments.class=ink.terraria.cmbwidget.BillStoreInstrumentedTest,ink.terraria.cmbwidget.BillDetailsSheetTest
```

覆盖旧数据库升级修正收款人、重扫后保留排除设置、账户隔离、详情开关保存与恢复，以及大字体操作。
