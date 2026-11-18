package xyz.etkn.tv;

import android.content.Context;
import android.content.pm.*;
import android.os.Build;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;

final class Updater {
    private static final String GITHUB_BASE = "https://github.com/" + BuildConfig.UPDATE_REPOSITORY;
    private static final URI MIRROR = URI.create(BuildConfig.UPDATE_MIRROR_BASE);
    private static final java.util.regex.Pattern APK_PATH = java.util.regex.Pattern.compile(
        java.util.regex.Pattern.quote("/" + BuildConfig.UPDATE_REPOSITORY + "/releases/download/")
            + "[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*\\.apk");

    static final class Release {
        final long code,size;final String version,notes,url,sha256;
        Release(JSONObject json)throws Exception {
            code=json.getLong("versionCode");size=json.getLong("size");version=json.getString("versionName");notes=json.optString("releaseNotes");sha256=json.getString("sha256").toLowerCase(Locale.ROOT);
            URI uri=new URI(json.getString("apkUrl"));
            if(!json.optString("packageName").equals(BuildConfig.APPLICATION_ID)||code<1||size<100000||size>200*1024*1024||!sha256.matches("[0-9a-f]{64}")||!"https".equals(uri.getScheme())||!"github.com".equals(uri.getRawAuthority())||uri.getRawPath()==null||!APK_PATH.matcher(uri.getRawPath()).matches()||uri.getRawQuery()!=null||uri.getRawFragment()!=null)throw new Exception("更新信息无效");
            url=BuildConfig.UPDATE_MIRROR_BASE+uri;
        }
    }
    static String manifestUrl() {
        return BuildConfig.UPDATE_MIRROR_BASE + GITHUB_BASE + "/releases/latest/download/update.json";
    }
    static boolean isMirrorUrl(URL url) {
        return "https".equals(url.getProtocol()) && MIRROR.getRawAuthority().equalsIgnoreCase(url.getAuthority())
            && url.getRef()==null;
    }
    private static HttpURLConnection connect(String address,boolean manifest)throws Exception {
        URL url=new URL(address);
        for(int i=0;i<6;i++){
            if(!isMirrorUrl(url))throw new Exception("更新镜像跳转到非允许地址，请稍后重试");
            HttpURLConnection conn=(HttpURLConnection)url.openConnection();conn.setConnectTimeout(15000);conn.setReadTimeout(30000);conn.setInstanceFollowRedirects(false);conn.setRequestProperty("User-Agent",EtknApi.USER_AGENT);
            if(manifest)conn.setRequestProperty("Cache-Control","no-cache");
            int code;
            try{code=conn.getResponseCode();}catch(Exception error){conn.disconnect();throw error;}
            if(code>=300&&code<400){String location=conn.getHeaderField("Location");conn.disconnect();if(location==null)throw new Exception("更新镜像的跳转地址无效，请稍后重试");url=new URL(url,location);continue;}
            if(code==404){conn.disconnect();throw new Exception("更新镜像暂未找到正式版本，请稍后重试");}
            if(code!=200){conn.disconnect();throw new Exception("更新镜像暂不可用（HTTP "+code+"），请稍后重试");}return conn;
        }throw new Exception("更新镜像跳转过多，请稍后重试");
    }
    static Release check()throws Exception {
        HttpURLConnection conn=connect(manifestUrl(),true);
        try{return new Release(new JSONObject(EtknApi.readText(conn.getInputStream())));}finally{conn.disconnect();}
    }
    static synchronized File download(Context context,Release release,java.util.function.Consumer<Integer> progress)throws Exception {
        File directory=context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);if(directory==null)throw new Exception("下载目录不可用");
        File partial=new File(directory,"etkn-tv-update.part"),apk=new File(directory,"etkn-tv-update.apk");
        HttpURLConnection conn=connect(release.url,false);MessageDigest hash=MessageDigest.getInstance("SHA-256");
        try{
            try(InputStream input=conn.getInputStream();OutputStream output=new FileOutputStream(partial)){
                byte[] buffer=new byte[32768];long size=0;int length,last=-1;
                while((length=input.read(buffer))!=-1){size+=length;if(size>release.size)throw new Exception("更新包大小不符");hash.update(buffer,0,length);output.write(buffer,0,length);int percent=(int)(size*100/release.size);if(percent!=last){last=percent;progress.accept(percent);}}
                if(size!=release.size||!hex(hash.digest()).equals(release.sha256))throw new Exception("更新包校验失败，请重新下载");
            }
            validatePackage(context,partial,release);
            if(apk.exists()&&!apk.delete())throw new Exception("无法替换旧更新包");
            if(!partial.renameTo(apk))throw new Exception("无法保存更新包");return apk;
        }finally{conn.disconnect();if(partial.exists())partial.delete();}
    }
    @SuppressWarnings("deprecation") static void validatePackage(Context context,File apk,Release release)throws Exception {
        validatePackage(context.getPackageManager(),context.getPackageName(),apk,release,Build.VERSION.SDK_INT);
    }
    // sdk comes from Build.VERSION.SDK_INT; the parameter also permits compatibility regression tests.
    @android.annotation.SuppressLint("NewApi") @SuppressWarnings("deprecation")
    static void validatePackage(PackageManager manager,String packageName,File apk,Release release,int sdk)throws Exception {
        // Android 9/10 archive parsing needs GET_SIGNATURES to collect certificates.
        int flags=PackageManager.GET_SIGNATURES|(sdk>=28?PackageManager.GET_SIGNING_CERTIFICATES:0);
        PackageInfo installed=manager.getPackageInfo(packageName,flags),candidate=manager.getPackageArchiveInfo(apk.getAbsolutePath(),flags);
        if(candidate==null||!packageName.equals(candidate.packageName)||version(candidate,sdk)!=release.code||version(candidate,sdk)<=version(installed,sdk))throw new Exception("更新包版本或应用标识不符");
        android.content.pm.Signature[] current=sdk>=28&&installed.signingInfo!=null?installed.signingInfo.getApkContentsSigners():installed.signatures;
        android.content.pm.Signature[] next=sdk>=28&&candidate.signingInfo!=null?candidate.signingInfo.getApkContentsSigners():candidate.signatures;
        if(current==null||next==null||current.length==0||next.length==0)throw new Exception("无法读取更新包签名，请重新下载或手动覆盖安装");
        if(current.length!=next.length)throw new Exception("更新包签名不符");
        Set<String> a=new HashSet<>(),b=new HashSet<>();for(android.content.pm.Signature sig:current)a.add(hex(MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())));for(android.content.pm.Signature sig:next)b.add(hex(MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())));if(!a.equals(b))throw new Exception("更新包签名不符");
    }
    @android.annotation.SuppressLint("NewApi") @SuppressWarnings("deprecation") static long version(PackageInfo info,int sdk){return sdk>=28?info.getLongVersionCode():info.versionCode;}
    static String hex(byte[] bytes){StringBuilder text=new StringBuilder();for(byte value:bytes)text.append(String.format(Locale.ROOT,"%02x",value&255));return text.toString();}
}
