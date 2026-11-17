# 验证

项目根目录执行：

```shell
./gradlew :app:assembleDebug :app:lintDebug
```

解析器检查（在 `app/src/main` 执行）：

```powershell
$classes = Join-Path $env:TEMP 'cmb-parser-classes'
javac --release 11 -encoding UTF-8 -d $classes java/ink/terraria/cmbwidget/mail/*.java verification/ParserChecks.java verification/MailboxChecks.java verification/MailboxBatchChecks.java
java -cp $classes ParserChecks
```

`MailboxChecks`、`MailboxBatchChecks` 通过 `CMB_TEST_USER` 和 `CMB_TEST_PASSWORD` 环境变量进行只读邮箱测试，不将凭据写入源码。

`BillMathChecks` 使用应用编译后的 `classes.jar` 与 Kotlin stdlib，检查退款抵扣、收入隔离、币种隔离及正负号。

后台同步的取消与连接中断检查：

```shell
./gradlew :app:testDebugUnitTest
```

`SyncControlTest` 覆盖取消前后建立连接、阻止已取消任务连接 IMAP、解除阻塞的 socket 读取，以及连接与取消并发时的资源释放。

`BillExclusionTest` 检查排除支出、退款和收入后的合计，以及恢复统计和币种隔离。

数据库升级和详情开关的真机测试使用独立包名，避免改动正式账本：

```shell
./gradlew :app:connectedDebugAndroidTest -PisolatedTests=true -Pandroid.testInstrumentationRunnerArguments.class=ink.terraria.cmbwidget.BillStoreInstrumentedTest,ink.terraria.cmbwidget.BillDetailsSheetTest
```

覆盖旧数据库升级修正收款人、重扫后保留排除设置、账户隔离、详情开关保存与恢复，以及大字体操作。
