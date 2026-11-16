package ink.terraria.cmbwidget.mail;

import java.io.*;
import java.nio.charset.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.*;

public final class MimeMail {
    public final String subject, from, id, body;
    public final LocalDate received;
    public MimeMail(byte[] bytes) throws IOException {
        String raw=new String(bytes,StandardCharsets.ISO_8859_1);
        Map<String,String> headers=headers(raw);
        subject=decodeHeader(headers.getOrDefault("subject",""));
        from=decodeHeader(headers.getOrDefault("from",""));
        id=headers.getOrDefault("message-id","");
        LocalDate day;
        try { day=ZonedDateTime.parse(headers.getOrDefault("date","").replaceAll("\\s*\\([^)]*\\)",""),DateTimeFormatter.RFC_1123_DATE_TIME).withZoneSameInstant(ZoneId.systemDefault()).toLocalDate(); }
        catch (DateTimeException e) { day=LocalDate.now(); }
        received=day;
        body=part(raw,0);
    }
    static Map<String,String> headers(String raw) {
        int split=raw.indexOf("\r\n\r\n"); if(split<0) split=raw.indexOf("\n\n");
        String head=split<0?raw:raw.substring(0,split);
        Map<String,String> map=new HashMap<>();
        for(String line:head.replaceAll("\r?\n[ \t]+"," ").split("\r?\n")) {
            int c=line.indexOf(':'); if(c>0) map.put(line.substring(0,c).toLowerCase(Locale.ROOT),line.substring(c+1).trim());
        }
        return map;
    }
    static String parameter(String header,String key,String fallback) {
        Matcher m=Pattern.compile("(?i)(?:^|;)\\s*"+key+"\\s*=\\s*(?:\"([^\"]*)\"|([^;\\s]+))").matcher(header);
        return m.find()?(m.group(1)!=null?m.group(1):m.group(2)):fallback;
    }
    static String part(String raw,int depth) throws IOException {
        if(depth>12) throw new IOException("邮件嵌套过深");
        Map<String,String> h=headers(raw);
        int split=raw.indexOf("\r\n\r\n"), offset=4;
        if(split<0) { split=raw.indexOf("\n\n"); offset=2; }
        if(split<0) return "";
        String payload=raw.substring(split+offset);
        String type=h.getOrDefault("content-type","text/plain");
        if(h.getOrDefault("content-disposition","").toLowerCase(Locale.ROOT).startsWith("attachment")) return "";
        if(type.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            String boundary=parameter(type,"boundary",""); if(boundary.isEmpty()) throw new IOException("邮件缺少 MIME 边界");
            String best=""; boolean alternative=type.toLowerCase(Locale.ROOT).contains("alternative");
            StringBuilder all=new StringBuilder();
            for(String p:payload.split("(?m)^--"+Pattern.quote(boundary)+"(?:--)?[ \t]*\\r?\\n?")) {
                if(!p.toLowerCase(Locale.ROOT).contains("content-type:")) continue;
                String value=part(p.replaceFirst("^\\r?\\n",""),depth+1);
                if(!value.trim().isEmpty()) { all.append(value).append('\n'); if(best.isEmpty() || headers(p).getOrDefault("content-type","").contains("text/html")) best=value; }
            }
            return alternative?best:all.toString();
        }
        if(!type.toLowerCase(Locale.ROOT).startsWith("text/")) return "";
        byte[] data=payload.getBytes(StandardCharsets.ISO_8859_1);
        String encoding=h.getOrDefault("content-transfer-encoding","");
        try {
            if(encoding.equalsIgnoreCase("base64")) data=Base64.getMimeDecoder().decode(data);
            else if(encoding.equalsIgnoreCase("quoted-printable")) data=quoted(data);
            String charset=parameter(type,"charset","UTF-8");
            return new String(data,Charset.forName(charset.equalsIgnoreCase("gb2312")?"GB18030":charset));
        } catch(IllegalArgumentException e) { throw new IOException("邮件编码无法识别",e); }
    }
    static byte[] quoted(byte[] bytes) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(int i=0;i<bytes.length;i++) {
            if(bytes[i]=='=' && i+1<bytes.length) {
                if(bytes[i+1]=='\n') { i++; continue; }
                if(bytes[i+1]=='\r' && i+2<bytes.length && bytes[i+2]=='\n') { i+=2; continue; }
                if(i+2<bytes.length) { int a=Character.digit((char)bytes[i+1],16),b=Character.digit((char)bytes[i+2],16); if(a>=0&&b>=0) {out.write(a*16+b);i+=2;continue;} }
            }
            out.write(bytes[i]);
        }
        return out.toByteArray();
    }
    static String decodeHeader(String text) throws IOException {
        Matcher m=Pattern.compile("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=").matcher(text.replaceAll("(?<=\\?=)\\s+(?==\\?)",""));
        StringBuffer result=new StringBuffer();
        while(m.find()) {
            try {
                byte[] data=m.group(2).equalsIgnoreCase("B")?Base64.getDecoder().decode(m.group(3)):quoted(m.group(3).replace('_',' ').getBytes(StandardCharsets.ISO_8859_1));
                m.appendReplacement(result,Matcher.quoteReplacement(new String(data,Charset.forName(m.group(1)))));
            } catch(IllegalArgumentException e) { throw new IOException("邮件标题编码无法识别",e); }
        }
        m.appendTail(result); return result.toString();
    }
}
