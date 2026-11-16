import ink.terraria.cmbwidget.mail.*;
import java.util.*;
import java.util.regex.*;
import java.math.BigDecimal;

/** Read-only integration test; credentials are supplied through process environment. */
public class MailboxChecks {
    public static void main(String[] args) throws Exception {
        int relevant=0, matched=0, ignored=0;
        Map<String,Integer> types=new TreeMap<>();
        try(var mail=new ImapClient("imap.qq.com",993,System.getenv("CMB_TEST_USER"),System.getenv("CMB_TEST_PASSWORD"))) {
            var ids=mail.search(0);
            for(long uid:ids) {
                var m=new MimeMail(mail.fetch(uid));
                if(!m.subject.matches(".*(账户.*通知|账单|交易.*通知|消费.*通知|还款.*通知).*")) { ignored++; continue; }
                if(!Pattern.compile("(?i)(?:<|^)(95555[^<>\\s]*@(?:[a-z0-9-]+\\.)*cmbchina\\.com)>?$").matcher(m.from.trim()).find()) throw new AssertionError("Sender mismatch #"+uid);
                relevant++;
                var entries=BillParser.parse(m.subject,m.body,m.received);
                if(entries.size()!=1) throw new AssertionError("Match count #"+uid);
                var e=entries.get(0);
                String text=BillParser.plain(m.body);
                // Independent amount oracle: final monetary value before balance/discount.
                String prefix=text.split("余额|已优惠")[0];
                Matcher amounts=Pattern.compile("(\\d+(?:,\\d{3})*(?:\\.\\d{1,2})?)\\s*元?(?:[，,。\\s]|$)").matcher(prefix);
                Long expected=null;
                while(amounts.find()) expected=new BigDecimal(amounts.group(1).replace(",","")).movePointRight(2).longValueExact();
                if(expected!=null && expected!=e.minor) throw new AssertionError("Amount mismatch #"+uid);
                if(e.card.isEmpty()) throw new AssertionError("Missing card #"+uid);
                types.merge(e.kind,1,Integer::sum); matched++;
                if(relevant%100==0) System.out.println("Validated "+relevant+" transaction MIME messages");
            }
            if(!mail.search(ids.get(ids.size()-1)).isEmpty()) throw new AssertionError("UID incremental filtering");
            System.out.println("Live TLS/IMAP/MIME: relevant="+relevant+", matched="+matched+", ignored="+ignored+"; "+types);
        }
    }
}
