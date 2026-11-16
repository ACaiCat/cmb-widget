package ink.terraria.cmbwidget.mail;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** TLS with hostname verification, EXAMINE and BODY.PEEK: never marks messages read. */
public final class ImapClient implements Closeable {
    private final SSLSocket socket;
    private final InputStream in;
    private final OutputStream out;
    private int tag=0;
    public String validity="";
    public ImapClient(String host,int port,String user,String password) throws IOException {
        Socket transport=new Socket();
        try {
            transport.connect(new InetSocketAddress(host,port),20000);
            socket=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(transport,host,port,true);
            socket.setSoTimeout(25000);
            SSLParameters params=socket.getSSLParameters(); params.setEndpointIdentificationAlgorithm("HTTPS"); socket.setSSLParameters(params);
            socket.startHandshake(); in=new BufferedInputStream(socket.getInputStream()); out=socket.getOutputStream();
            if(!line().startsWith("* OK")) throw new IOException("IMAP 服务未就绪");
            command("LOGIN "+quote(user)+" "+quote(password));
            select("INBOX");
        } catch(IOException | RuntimeException e) { transport.close(); throw e; }
    }
    public static final class Folder {
        public final String wire, name;
        Folder(String wire) { this.wire=wire; this.name=decodeFolder(wire); }
    }
    public List<Folder> folders() throws IOException {
        List<Folder> folders=new ArrayList<>();
        for(String line:command("LIST \"\" \"*\"")) {
            Matcher m=Pattern.compile("(?i)^\\* LIST \\(([^)]*)\\) (?:\"[^\"]*\"|NIL) (.+)$").matcher(line);
            if(!m.find() || m.group(1).toLowerCase(Locale.ROOT).contains("\\noselect")) continue;
            String name=m.group(2);
            if(name.startsWith("\"") && name.endsWith("\"")) name=name.substring(1,name.length()-1).replace("\\\"","\"").replace("\\\\","\\");
            folders.add(new Folder(name));
        }
        folders.sort(Comparator.comparingInt(f -> f.wire.equalsIgnoreCase("INBOX")?1:0));
        return folders;
    }
    public void select(String folder) throws IOException {
        validity="";
        for(String s:command("EXAMINE "+quote(folder))) {
            Matcher m=Pattern.compile("UIDVALIDITY (\\d+)").matcher(s); if(m.find()) validity=m.group(1);
        }
        if(validity.isEmpty()) throw new IOException("邮箱未提供 UIDVALIDITY");
    }
    static String decodeFolder(String wire) {
        Matcher m=Pattern.compile("&([^-]*)-").matcher(wire); StringBuffer b=new StringBuffer();
        while(m.find()) {
            String value="&";
            if(!m.group(1).isEmpty()) try { value=new String(Base64.getDecoder().decode(m.group(1).replace(',','/')),StandardCharsets.UTF_16BE); } catch(IllegalArgumentException ignored) { value=m.group(); }
            m.appendReplacement(b,Matcher.quoteReplacement(value));
        }
        m.appendTail(b); return b.toString();
    }
    private static String quote(String s) throws IOException {
        if(s.indexOf('\r')>=0||s.indexOf('\n')>=0||s.indexOf('\0')>=0) throw new IOException("邮箱配置包含非法字符");
        return "\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\"";
    }
    private String line() throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream(); int c;
        while((c=in.read())!=-1 && c!='\n') { if(b.size()>1024*1024) throw new IOException("IMAP 响应过大"); if(c!='\r') b.write(c); }
        if(c==-1) throw new EOFException("邮箱连接中断");
        return b.toString("UTF-8");
    }
    private List<String> command(String command) throws IOException { return exchange(command,null); }
    private List<String> exchange(String command,ByteArrayOutputStream literal) throws IOException {
        String key="A"+(++tag); out.write((key+" "+command+"\r\n").getBytes(StandardCharsets.UTF_8)); out.flush();
        List<String> lines=new ArrayList<>();
        while(true) {
            String s=line(); lines.add(s);
            Matcher m=Pattern.compile("\\{(\\d+)\\}$").matcher(s);
            if(m.find()) {
                int n=Integer.parseInt(m.group(1)); if(n>8*1024*1024) throw new IOException("邮件超过 8 MB，请单独处理");
                ByteArrayOutputStream listName=command.startsWith("LIST ")?new ByteArrayOutputStream():null;
                byte[] block=new byte[Math.min(n,8192)];
                while(n>0) { int got=in.read(block,0,Math.min(n,block.length)); if(got<0) throw new EOFException(); if(literal!=null) literal.write(block,0,got); if(listName!=null) listName.write(block,0,got); n-=got; }
                if(listName!=null) lines.set(lines.size()-1,s.substring(0,m.start())+quote(listName.toString("US-ASCII")));
            }
            if(s.startsWith(key+" ")) {
                if(!s.startsWith(key+" OK")) throw new IOException(command.startsWith("LOGIN")?"邮箱登录失败，请检查 IMAP 开关和授权码":"IMAP 操作失败，请稍后重试");
                return lines;
            }
            if(s.startsWith("* BYE")) throw new IOException("邮箱主动断开连接");
        }
    }
    public List<Long> search(long after) throws IOException {
        List<Long> ids=new ArrayList<>();
        // QQ IMAP sender search reliably matches the local part, including 95555ad marketing.
        for(String s:command("UID SEARCH UID "+(after+1)+":* FROM \"95555\"")) {
            if(s.startsWith("* SEARCH")) for(String n:s.substring(8).trim().split(" +")) {
                if(!n.isEmpty()) { long id=Long.parseLong(n); if(id>after) ids.add(id); }
            }
        }
        Collections.sort(ids); return ids;
    }
    public byte[] fetch(long uid) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); exchange("UID FETCH "+uid+" (BODY.PEEK[])",bytes);
        if(bytes.size()==0) throw new IOException("邮件已移动或无法读取，请重新同步");
        return bytes.toByteArray();
    }
    public Map<Long,byte[]> fetchBatch(List<Long> ids) throws IOException {
        if(ids.isEmpty()) return Collections.emptyMap();
        StringJoiner set=new StringJoiner(","); for(long id:ids) set.add(Long.toString(id));
        String key="A"+(++tag);
        out.write((key+" UID FETCH "+set+" (UID BODY.PEEK[])\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush();
        Map<Long,byte[]> result=new HashMap<>();
        Long uid=null; byte[] data=null;
        while(true) {
            String s=line();
            Matcher u=Pattern.compile("\\bUID (\\d+)").matcher(s); if(u.find()) uid=Long.parseLong(u.group(1));
            Matcher literal=Pattern.compile("\\{(\\d+)\\}$").matcher(s);
            if(literal.find()) {
                int length=Integer.parseInt(literal.group(1));
                if(length>8*1024*1024) throw new IOException("邮件超过 8 MB，请单独处理");
                data=new byte[length]; int offset=0;
                while(offset<length) { int n=in.read(data,offset,length-offset); if(n<0) throw new EOFException(); offset+=n; }
            }
            if(uid!=null && data!=null) { result.put(uid,data); uid=null; data=null; }
            if(s.startsWith(key+" ")) {
                if(!s.startsWith(key+" OK")) throw new IOException("批量读取邮件失败");
                if(result.size()!=ids.size()) throw new IOException("部分邮件已移动，请重新同步");
                return result;
            }
            if(s.startsWith("* BYE")) throw new IOException("邮箱主动断开连接");
        }
    }
    @Override public void close() throws IOException { socket.close(); }
}
