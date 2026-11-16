import ink.terraria.cmbwidget.mail.*;
public class MailboxBatchChecks {
    public static void main(String[] args) throws Exception {
        try(var mail=new ImapClient("imap.qq.com",993,System.getenv("CMB_TEST_USER"),System.getenv("CMB_TEST_PASSWORD"))) {
            var folders=mail.folders(); int tested=0;
            for(var folder:folders) {
                mail.select(folder.wire); var ids=mail.search(0);
                if(ids.isEmpty()) continue;
                var batch=ids.subList(Math.max(0,ids.size()-30),ids.size());
                var messages=mail.fetchBatch(batch);
                for(var uid:batch) {
                    var m=new MimeMail(messages.get(uid));
                    if(m.subject.isEmpty()) throw new AssertionError("MIME subject");
                    tested++;
                }
                var last=batch.get(batch.size()-1);
                if(!java.util.Arrays.equals(messages.get(last),mail.fetch(last))) throw new AssertionError("Batch differs from single FETCH");
            }
            System.out.println("All-folder LIST/EXAMINE and batch BODY.PEEK verified: folders="+folders.size()+", raw messages="+tested);
        }
    }
}
