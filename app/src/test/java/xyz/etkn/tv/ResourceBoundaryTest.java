package xyz.etkn.tv;

import org.json.*;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class ResourceBoundaryTest {
    @Test public void expandedVersionsNeverInheritParentResourceIdentity()throws Exception {
        JSONObject shell=new JSONObject().put("title","Example").put("season_number",1).put("sha1","wrong").put("source_id",999).put("files",new JSONArray().put("wrong"));
        shell.put("versions",new JSONArray().put(new JSONObject().put("sha1","first").put("source_id",1)).put(new JSONObject().put("sha1","second").put("source_id",2)));
        List<CloudResources.Resource> rows=CloudResources.parse("shared_pool",new JSONObject().put("resources",new JSONArray().put(shell)),1);
        assertEquals(2,rows.size());assertEquals("first",rows.get(0).source.getString("sha1"));assertEquals(2,rows.get(1).source.getInt("source_id"));
        assertFalse(rows.get(0).source.has("files"));assertEquals("Example",rows.get(0).source.getString("title"));
    }
    @Test public void seasonSelectionIncludesSpecialsAndExcludesUnknownSharedSeasons()throws Exception {
        JSONArray items=new JSONArray().put(new JSONObject().put("season_number",0)).put(new JSONObject().put("season_number",1)).put(new JSONObject().put("title","Unknown"));
        List<CloudResources.Resource> rows=CloudResources.parse("shared_pool",new JSONObject().put("resources",items),0);
        assertEquals(1,rows.size());assertEquals(Integer.valueOf(0),CloudResources.season(rows.get(0).source));
    }
    @Test public void groupedResourcePreservesExactAssetMap()throws Exception {
        JSONObject source=new JSONObject().put("sha1","selected").put("logical_group",new JSONObject().put("logical_group_id","g").put("content_version_key","v").put("best_asset_map","{\"1\":{\"sha1\":\"episode\"}}"));
        JSONObject body=CloudResources.sharedSource(source);
        assertEquals("selected",body.getString("sha1"));assertEquals("g",body.getString("logical_group_id"));assertEquals("episode",body.getJSONObject("best_asset_map").getJSONObject("1").getString("sha1"));
    }
    @Test public void updateManifestOnlyAcceptsConfiguredReleaseRepository()throws Exception {
        String apk="https://github.com/Alumos/etkn-tv/releases/download/revised-v0.2/etkn-tv-revised-0.2.apk";
        JSONObject manifest=new JSONObject().put("packageName","xyz.etkn.tv.revised").put("versionCode",2).put("versionName","0.2").put("size",200000).put("sha256","a".repeat(64)).put("apkUrl",apk);
        Updater.Release release=new Updater.Release(manifest);
        assertEquals(2,release.code);
        assertEquals("https://gh-proxy.com/"+apk,release.url);
        for(String url:new String[]{apk.replace("Alumos","hbq0405"),apk.replace("https:","http:"),apk+"?redirect=bad",apk+"#fragment",
                apk.replace("github.com","github.com:443"),apk.replace("github.com","github.com:"),apk.replace("github.com","user"+"@"+"github.com"),apk.replace("github.com","github.com.evil.example"),
                "https://gh-proxy.com/"+apk,apk.replace("revised-v0.2/","%2e%2e/"),apk.replace("revised-v0.2/","revised-v0.2/nested/"),apk.replace(".apk",".zip")}) {
            manifest.put("apkUrl",url);assertThrows(url,Exception.class,()->new Updater.Release(manifest));
        }
        manifest.put("apkUrl",apk).put("packageName","xyz.etkn.tv");
        assertThrows(Exception.class,()->new Updater.Release(manifest));
    }
    @Test public void updateRequestsAndRedirectsStayOnMirror()throws Exception {
        String address="https://gh-proxy.com/https://github.com/Alumos/etkn-tv/releases/latest/download/update.json";
        assertEquals(address,Updater.manifestUrl());
        assertTrue(Updater.isMirrorUrl(new java.net.URL(address)));
        assertTrue(Updater.isMirrorUrl(new java.net.URL(address+"?cache=1")));
        for(String url:new String[]{address.replace("https://gh-proxy.com/",""),address.replace("https://gh-proxy.com/","http://gh-proxy.com/"),
                address.replace("gh-proxy.com","gh-proxy.com.evil.example"),address.replace("gh-proxy.com","user"+"@"+"gh-proxy.com"),
                address.replace("gh-proxy.com","gh-proxy.com:443"),address+"#fragment","https://release-assets.githubusercontent.com/example"}) {
            assertFalse(url,Updater.isMirrorUrl(new java.net.URL(url)));
        }
    }
}
