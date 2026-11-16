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