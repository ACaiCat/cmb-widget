package ink.terraria.cmbwidget.mail;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Strict transaction patterns: balances and marketing amounts are never transactions. */
public final class BillParser {
    public static final class Entry {
        public final String date, merchant, currency, card, kind;
        public final long minor;
        public Entry(String date, String merchant, String currency, String card, String kind, long minor) {
            this.date=date; this.merchant=merchant; this.currency=currency; this.card=card; this.kind=kind; this.minor=minor;
        }
    }
    private static final String MONEY = "([\\d,]+(?:\\.\\d{1,2})?)";
    private static final Pattern DATE = Pattern.compile("(?:(20\\d{2})[年/.-])?(\\d{1,2})[月/.-](\\d{1,2})日?\\s*(?:(\\d{1,2})[:时](\\d{2})(?:[:分](\\d{2}))?秒?)?");
    private static final Pattern ACCOUNT = Pattern.compile("(?:账户|尾号|末四位|卡号|储蓄卡)(?:为|是)?[：:（(\\s*]*(\\d{4})(?!\\d)");
    private static final String ACTION = "一网通支付退货入账|撤销实时转出|转账汇款|退货入账|实时转至他行|一网通支付|快捷支付|银联消费|网上支付|消费|支出|转出|转入|收入|退款|退货|退汇|取款|取现|扣款|付款|汇款|转账|收款|还款|存款|入账|支付|利息|交易";
    private static final Pattern TRANSACTION = Pattern.compile("("+ACTION+")\\s*(?:人民币|RMB|CNY|美元|USD|港币|HKD|欧元|EUR|[¥￥$])?\\s*"+MONEY+"\\s*(元|人民币|美元|港币|欧元|CNY|USD|HKD|EUR)?",Pattern.CASE_INSENSITIVE);
    private static final Pattern LABELED = Pattern.compile("(?:交易金额|支出金额|收入金额|退款金额)[：:\\s]*(人民币|RMB|CNY|美元|USD|港币|HKD|欧元|EUR|[¥￥$])?\\s*"+MONEY+"\\s*(元|美元|港币|欧元)?",Pattern.CASE_INSENSITIVE);

    public static List<Entry> parse(String subject, String body, LocalDate received) {
        String text = plain(body);
        List<Entry> result = new ArrayList<>();
        // Notifications can contain several separate account changes.
        String[] chunks = text.split("(?=(?:您(?:的)?(?:账户|尾号)|您卡|卡号[：:]))");
        for (String chunk : chunks) {
            Matcher amount = TRANSACTION.matcher(chunk);
            String action, number, currencyText;
            int end, start;
            if (amount.find()) {
                action=amount.group(1); number=amount.group(2); currencyText=amount.group(); end=amount.end(); start=amount.start();
            } else {
                Matcher labeled=LABELED.matcher(chunk);
                if (!labeled.find()) {
                    // Cashback notification separates its action and amount with account information.
                    if(chunk.contains("支付鼓励金") && chunk.contains("成功入账")) {
                        labeled=Pattern.compile("(人民币)\\s*"+MONEY+"\\s*(元)").matcher(chunk);
                        if(!labeled.find()) continue;
                    } else continue;
                }
                Matcher a=Pattern.compile(ACTION).matcher(chunk);
                if (!a.find()) continue;
                action=a.group(); number=labeled.group(2); currencyText=labeled.group(); end=labeled.end(); start=labeled.start();
            }
            Matcher date=DATE.matcher(chunk);
            if (!date.find()) continue;
            LocalDate day;
            try {
                int year=date.group(1)==null?received.getYear():Integer.parseInt(date.group(1));
                day=LocalDate.of(year,Integer.parseInt(date.group(2)),Integer.parseInt(date.group(3)));
                if (date.group(1)==null && day.isAfter(received.plusDays(2))) day=day.minusYears(1);
            } catch (DateTimeException | NumberFormatException e) { continue; }
            String time=date.group(4)==null?"00:00:00":String.format(Locale.ROOT,"%02d:%02d:%02d",Integer.parseInt(date.group(4)),Integer.parseInt(date.group(5)),date.group(6)==null?0:Integer.parseInt(date.group(6)));
            try { LocalTime.parse(time); } catch (DateTimeException e) { continue; }
            Matcher card=ACCOUNT.matcher(chunk);
            String last=card.find()?card.group(1):"";
            String merchant=action;
            String prefix=chunk.substring(Math.min(date.end(),start), start);
            Matcher at=Pattern.compile("(?:在|向|通过)(.+)").matcher(prefix);
            if (at.find()) merchant=at.group(1).replaceAll("(?:发生)?[，,。；;\\s]*$","").trim();
            if (merchant.equals(action)) {
                Matcher note=Pattern.compile("(?:备注[：:]|元?[（(])(.+?)[）)]?$|(?:付方|收款人)[：:\\s]*([^，。；;\\n]+)").matcher(chunk.substring(end));
                if(note.find()) merchant=(note.group(1)!=null?note.group(1):note.group(2)).trim();
            }
            Matcher named=Pattern.compile("(?:商户(?:名称)?|交易地点|交易摘要|对方户名)[：:\\s]+([^，。；;\\n]+)").matcher(chunk);
            if (named.find()) merchant=named.group(1).trim();
            if (merchant.length()>160) merchant=merchant.substring(0,160);
            String kind=action.matches(".*退货.*|退款|退汇|撤销实时转出") || (action.equals("交易") && chunk.contains("支付退货"))?"REFUND":chunk.contains("朝朝宝")?"TRANSFER":action.matches("转入|收入|收款|存款|入账|利息") || chunk.contains("支付鼓励金")?"INCOME":action.equals("还款")?"REPAYMENT":"EXPENSE";
            if(chunk.contains("支付鼓励金")) merchant="招行支付鼓励金";
            String currency=currencyText.matches("(?is).*(美元|USD|\\$).*")?"USD":currencyText.matches("(?is).*(港币|HKD).*")?"HKD":currencyText.matches("(?is).*(欧元|EUR).*")?"EUR":"CNY";
            try {
                long minor=new BigDecimal(number.replace(",","")).movePointRight(2).longValueExact();
                if (minor>0) result.add(new Entry(day+"T"+time,merchant,currency,last,kind,minor));
            } catch (ArithmeticException | NumberFormatException ignored) { }
        }
        return result;
    }

    public static String plain(String html) {
        String s=html.replaceAll("(?is)<(style|script)\\b[^>]*>.*?</\\1>","")
            .replaceAll("(?i)<br\\s*/?>|</(?:tr|p|div)>","\n").replaceAll("<[^>]+>"," ")
            .replace("&nbsp;"," ").replace("&#160;"," ").replace("&amp;","&").replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"");
        Matcher entities=Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);").matcher(s);
        StringBuffer decoded=new StringBuffer();
        while (entities.find()) {
            try { String n=entities.group(1); int cp=n.startsWith("x")?Integer.parseInt(n.substring(1),16):Integer.parseInt(n); entities.appendReplacement(decoded,Matcher.quoteReplacement(new String(Character.toChars(cp)))); }
            catch (IllegalArgumentException e) { entities.appendReplacement(decoded,""); }
        }
        entities.appendTail(decoded);
        return decoded.toString().replace('\u3000',' ').replaceAll("[\\t\\x0B\\f\\r ]+"," ").trim();
    }
}
