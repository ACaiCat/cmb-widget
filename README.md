# 招行账单小组件

通过 IMAP 读取招行账户变动邮件，自动提取、保存账单，在桌面查看每日支出、月度支出和交易明细

## 截图

<img width="320" alt="bf48f3111ed51803b911c86019fd44ae_720" src="https://github.com/user-attachments/assets/ca8f0b99-ca48-4a07-9341-e4bc435e01e1" />
<img width="320" alt="9589bbcbe3905fe7bbf2738b2fdaaf1e_720" src="https://github.com/user-attachments/assets/25113ed2-47ab-45f4-bc70-b9fe82c94632" />
<img width="320" alt="1916491e207c7b57f8ff7cee48fbee3c_720" src="https://github.com/user-attachments/assets/2a46c9ca-2301-4561-8682-191496e101df" />

## 使用

1. 需要在招商银行APP打开账单邮件提醒功能
2. 开启邮箱 IMAP，在 APP 中填写服务器、账号和授权码
3. 长按桌面，添加「今日支出」或「今日账单」小组件

> [!NOTE]
> 关闭省电策略效果更佳

## 构建

```shell
./gradlew :app:assembleRelease
```
