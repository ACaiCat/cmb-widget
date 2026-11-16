import ink.terraria.cmbwidget.mail.*;
import java.time.*;
import java.nio.file.*;
import java.nio.charset.*;
import java.util.*;

/** Run with javac/java; fixtures use invented account and merchant details. */
public class ParserChecks {
    static void check(String text, long minor, String kind, String merchant) {
        var entries=BillParser.parse("一卡通账户变动通知",text,LocalDate.of(2026,1,2));
        if(entries.size()!=1) throw new AssertionError("Expected one entry: "+text);
        var e=entries.get(0);
        if(e.minor!=minor || !e.kind.equals(kind) || !e.merchant.equals(merchant))
            throw new AssertionError(text+" -> "+e.minor+" "+e.kind+" "+e.merchant);
    }
    public static void main(String[] args) throws Exception {
        check("您账户1234于01月02日14:27在财付通-微信支付-示例商店快捷支付3.00元，余额837.33",300,"EXPENSE","财付通-微信支付-示例商店");
        check("您的招商银行储蓄卡1234于01月02日15:55，在哈啰-顺风车一网通支付人民币29.91元，已优惠1.49元，余额0.00元。",2991,"EXPENSE","哈啰-顺风车");
        check("您账户1234于01月02日12:18在哈啰-顺风车发生一网通支付退货入账人民币21.50元，余额21.50元",2150,"REFUND","哈啰-顺风车");
        check("您尾号1234的账户于01月02日发起朝朝宝转入，交易金额：人民币3,000元。",300000,"TRANSFER","转入");
        check("您账户1234于01月02日19:25银联扣款人民币1.52元，余额3.08元（银联在线支付，示例店）",152,"EXPENSE","银联在线支付，示例店");
        var rollover=BillParser.parse("", "您账户1234于12月31日23:59消费人民币1.00元，余额99.00",LocalDate.of(2026,1,1)).get(0);
        if(!rollover.date.startsWith("2025-12-31")) throw new AssertionError("year rollover");
        if(!BillParser.parse("优惠","存款年利率3.00%，余额200.00元",LocalDate.now()).isEmpty()) throw new AssertionError("marketing");
        if(!BillParser.parse("","您账户1234于02月30日12:00消费3.00元",LocalDate.now()).isEmpty()) throw new AssertionError("bad date");
        if(!BillParser.parse("","您账户1234于01月01日25:00消费3.00元",LocalDate.now()).isEmpty()) throw new AssertionError("bad time");
        String mime="Subject: =?UTF-8?B?5rWL6K+V?=\r\nContent-Type: multipart/alternative; boundary=abc\r\n\r\n--abc\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nplain\r\n--abc\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\nPGI+aHRtbDwvYj4=\r\n--abc--\r\n";
        var m=new MimeMail(mime.getBytes(StandardCharsets.UTF_8));
        if(!m.subject.equals("测试")||!m.body.trim().equals("<b>html</b>")) throw new AssertionError("MIME alternatives");
        if(args.length>0) {
            int count=0,miss=0; Map<String,Integer> kinds=new TreeMap<>();
            for(String line:Files.readAllLines(Path.of(args[0]),StandardCharsets.UTF_8)) {
                String[] fields=line.split("\t");
                String body=new String(Base64.getDecoder().decode(fields[1]),StandardCharsets.UTF_8);
                var entries=BillParser.parse("一卡通账户变动通知",body,LocalDate.parse(fields[0])); count++;
                if(entries.size()!=1) { miss++; System.out.println("Unmatched fixture #"+count); }
                else {
                    var e=entries.get(0); kinds.merge(e.kind,1,Integer::sum);
                    if(e.card.isEmpty()) throw new AssertionError("card fixture #"+count);
                    String prefix=body.split("余额|已优惠")[0];
                    var amounts=java.util.regex.Pattern.compile("(\\d+(?:,\\d{3})*(?:\\.\\d{1,2})?)\\s*元?(?:[，,。\\s]|$)").matcher(prefix);
                    Long expected=null;
                    while(amounts.find()) expected=new java.math.BigDecimal(amounts.group(1).replace(",","")).movePointRight(2).longValueExact();
                    if(expected!=null && expected!=e.minor) throw new AssertionError("amount fixture #"+count);
                }
            }
            System.out.println("Real mail validation: "+count+" mails, "+miss+" unmatched; "+kinds);
            if(miss>0) throw new AssertionError("Unmatched mail");
        }
        System.out.println("Parser and MIME checks passed");
    }
}
