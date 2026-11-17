package xyz.etkn.tv;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ImageView;
import android.widget.SeekBar;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Loopback fixture only; run in a separately signed application data area. */
public class UserPreferencesMusicTest {
    final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    final Context context=instrumentation.getTargetContext();
    TvActivity host;
    void main(Runnable action){instrumentation.runOnMainSync(action);}
    void await(BooleanSupplier ready)throws Exception{long end=SystemClock.elapsedRealtime()+15000;while(SystemClock.elapsedRealtime()<end){boolean[] result={false};main(()->result[0]=ready.getAsBoolean());if(result[0])return;SystemClock.sleep(80);}fail("Timed out waiting for fixture behavior");}
    void setup(Fixture fixture)throws Exception{
        context.getSharedPreferences("session",0).edit().clear().commit();
        context.getSharedPreferences("appearance",0).edit().clear().commit();
        SessionStore.endTemporarySession();LoginGate.satisfy();TvAppearance.current=new JSONObject();TvAppearance.saving=false;
        SessionStore session=new SessionStore(context);session.saveSession(fixture.origin(),"fixture","音乐测试","loopback-only",SessionStore.PERSIST_FULL);
        context.getSharedPreferences("session",0).edit().putBoolean("mediaEntryVerified",true).commit();
    }
    void close(){if(host!=null)main(()->{MusicPlayback.stop();host.finish();});SessionStore.endTemporarySession();}
    void call(String name,Class<?>[] types,Object... args)throws Exception{java.lang.reflect.Method method=host.getClass().getDeclaredMethod(name,types);method.setAccessible(true);main(()->{try{method.invoke(host,args);}catch(Exception error){throw new RuntimeException(error);}});}
    Object field(String name)throws Exception{java.lang.reflect.Field field=host.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(host);}
    void screenshot(String name)throws Exception{SystemClock.sleep(700);File directory=new File(context.getExternalFilesDir(null),"preferences-music");assertTrue(directory.isDirectory()||directory.mkdirs());android.graphics.Bitmap frame=instrumentation.getUiAutomation().takeScreenshot();assertNotNull(frame);try(FileOutputStream output=new FileOutputStream(new File(directory,name+".png"))){frame.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}frame.recycle();}

    @Test public void sharedLockExcludesAdministratorChoiceAndKeepsCurrentSession()throws Exception{
        try(Fixture fixture=new Fixture()){
            setup(fixture);host=(SettingsActivity)instrumentation.startActivitySync(new Intent(context,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            await(()->TvAppearance.current.optInt("card_size",-1)==2);
            fixture.preferences.put("card_size",4).put("shadow_color","#bb3366");
            await(()->new TvPreferences(context,host.api).imageSize()==4);
            call("saveClient",new Class<?>[]{String.class,Object.class},"shadow_color","#123456");
            await(()->"#123456".equals(TvAppearance.current.optString("shadow_color"))&&!TvAppearance.saving);
            assertEquals("#123456",fixture.preferences.getString("shadow_color"));
            call("changeThemeLock",new Class<?>[]{boolean.class},true);
            await(()->TvAppearance.locked(context,fixture.origin())&&!TvAppearance.saving);
            fixture.preferences.put("card_size",0).put("shadow_color","#ff0000");
            fixture.remember=false;
            await(()->!host.api.session.rememberAdmin(fixture.origin(),"fixture")&&host.api.session.persist()==SessionStore.PERSIST_ACCOUNT);
            assertEquals("同步关闭保存不能退出当前会话","loopback-only",host.api.session.token());
            assertEquals(4,new TvPreferences(context,host.api).imageSize());
            List<?> controls=(List<?>)field("sharedControls");for(Object control:controls)assertFalse(((View)control).isEnabled());
            host.api.get("/api/tv/Users/fixture/Items/Latest");
            assertTrue(fixture.lockedHeader.contains("jpn"));
            call("saveClient",new Class<?>[]{String.class,Object.class},"card_size",1);
            assertEquals(0,fixture.preferences.getInt("card_size"));
            screenshot("locked-preferences");
            call("changeThemeLock",new Class<?>[]{boolean.class},false);
            await(()->!TvAppearance.locked(context,fixture.origin())&&new TvPreferences(context,host.api).imageSize()==0);
            // Reuse the pre-existing password gate after clearing only persisted credentials.
            assertEquals(SessionStore.PERSIST_ACCOUNT,host.api.savedAccountPolicy("fixture"));
            SessionStore.endTemporarySession();
            try{host.api.session.activate("fixture");fail("Password should be required");}catch(Exception expected){assertTrue(expected.getMessage().contains("密码"));}
        }finally{close();}
    }

    @Test public void musicPagePlaysAudioPaginatesAndReportsCompletion()throws Exception{
        try(Fixture fixture=new Fixture()){
            setup(fixture);host=(MusicActivity)instrumentation.startActivitySync(new Intent(context,MusicActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            await(()->find(host.content,"最近听的")!=null&&find(host.content,"最近常听")!=null);
            screenshot("music-recommended");
            TvActivity browser=host;LinearLayout categories=(LinearLayout)field("musicMenu");main(()->categories.getChildAt(1).performClick());await(()->find(host.content,"测试专辑")!=null);
            GridLayout albums=(GridLayout)field("listing");assertEquals(1,albums.getChildCount());
            Instrumentation.ActivityMonitor playerMonitor=instrumentation.addMonitor(MusicPlayerActivity.class.getName(),null,false);
            main(()->albums.getChildAt(0).performClick());
            MusicPlayerActivity albumPlayer=(MusicPlayerActivity)instrumentation.waitForMonitorWithTimeout(playerMonitor,10000);assertNotNull("点击专辑应直接进入播放页",albumPlayer);host=albumPlayer;
            MusicPlayback[] albumPlayback={null};await(()->MusicPlayback.active()&&(albumPlayback[0]=MusicPlayback.get(context)).playing()&&albumPlayback[0].queue.items.size()==3);
            assertEquals("专辑首曲应立即播放","歌曲 1",albumPlayback[0].queue.current().optString("Name"));assertTrue(fixture.albumRequested);
            assertEquals("默认显示歌词",View.VISIBLE,((View)field("lyricPanel")).getVisibility());main(()->((Button)fieldUnchecked("lyricToggle")).performClick());
            await(()->{ListView list=(ListView)fieldUnchecked("queue");View first=list.getChildAt(0);return first!=null&&find(first,"0:08")!=null&&findClass(first,"SpectrumIcon")!=null;});
            ListView albumQueue=(ListView)field("queue");View firstSong=albumQueue.getChildAt(0);TextView queueTitle=(TextView)field("queueTitle");SeekBar progress=(SeekBar)field("seek");assertNull("歌曲列表外层不保留大卡片底板",((View)albumQueue.getParent()).getBackground());assertNotNull("每首歌保留自己的小卡片",firstSong.getBackground());assertTrue("队列列表裁剪子项，避免卡片盖住标题",albumQueue.getClipChildren()&&albumQueue.getClipToPadding());assertFalse("播放进度条不参与遥控器焦点导航",progress.isFocusable());
            android.graphics.drawable.StateListDrawable queueSelector=(android.graphics.drawable.StateListDrawable)albumQueue.getSelector();main(()->{assertFalse("播放焦点在左侧时队列不能显示聚焦框",albumQueue.hasFocus());assertTrue(queueSelector.getCurrent() instanceof android.graphics.drawable.ColorDrawable);});
            main(albumQueue::requestFocus);await(albumQueue::hasFocus);main(()->{assertTrue(queueSelector.getCurrent() instanceof FocusPulse);Rect heading=new Rect(),row=new Rect();assertTrue(queueTitle.getGlobalVisibleRect(heading));assertTrue(firstSong.getGlobalVisibleRect(row));assertTrue("播放列表卡片必须位于标题下方",row.top>=heading.bottom);});screenshot("music-queue-focused");main(()->((View)fieldUnchecked("pause")).requestFocus());await(()->!albumQueue.hasFocus()&&queueSelector.getCurrent() instanceof android.graphics.drawable.ColorDrawable);screenshot("music-queue-unfocused");
            LinearLayout playbackControls=(LinearLayout)field("controls");assertEquals("保留原控件并增加歌词切换",6,playbackControls.getChildCount());for(int i=0;i<playbackControls.getChildCount();i++)assertNotEquals("音量",playbackControls.getChildAt(i).getContentDescription());
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);await(()->albumPlayer.isFinishing()&&!MusicPlayback.active());host=browser;instrumentation.removeMonitor(playerMonitor);
            List<JSONObject> tracks=Arrays.asList(fixture.track(1),fixture.track(2));
            MusicPlayback[] playback={null};main(()->{playback[0]=MusicPlayback.get(context);playback[0].play(tracks,tracks.get(0),"/api/tv/Users/fixture/Music?Tab=songs",3);});
            host=(MusicPlayerActivity)instrumentation.startActivitySync(new Intent(context,MusicPlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(()->playback[0].playing()&&playback[0].position()>250&&playback[0].queue.items.size()==3);
            assertTrue(fixture.audioRequests>0);
            main(()->((Button)fieldUnchecked("lyricToggle")).performClick());
            View pause=(View)field("pause"),mode=(View)field("mode");Rect rect=new Rect();
            assertTrue(pause.getGlobalVisibleRect(rect));assertEquals(pause.getHeight(),rect.height());
            assertTrue(mode.getGlobalVisibleRect(rect));assertEquals("播放选项必须完整可见",mode.getHeight(),rect.height());
            main(()->assertIconButtons(host.root));main(()->playback[0].mode(2));screenshot("music-mode-all");main(()->playback[0].mode(3));screenshot("music-mode-one");main(()->playback[0].mode(0));
            main(pause::requestFocus);instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER);
            await(()->!playback[0].playing());instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER);
            await(()->playback[0].playing());
            screenshot("music-player");
            main(()->playback[0].toggle());await(()->!playback[0].playing());
            await(()->findClass(((ListView)fieldUnchecked("queue")).getChildAt(0),"SpectrumIcon")==null);
            fixture.preferences.put("mode","light");await(()->!host.appearanceDark());main(()->assertIconButtons(host.root));screenshot("music-player-light");
            fixture.preferences.put("mode","dark");await(()->host.appearanceDark());
            main(()->playback[0].toggle());await(()->playback[0].playing());
            main(()->playback[0].seek(7600));
            await(()->playback[0].queue.index==1&&playback[0].playing());
            await(()->fixture.completed>0);
            main(()->playback[0].remove(0));assertEquals(0,playback[0].queue.index);
            main(()->playback[0].mode(3));main(()->playback[0].seek(7600));
            await(()->fixture.completed>=2&&playback[0].position()<5000&&playback[0].playing());
            assertEquals(0,playback[0].queue.index);
            int previousStops=fixture.stops;
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            await(()->host.isFinishing()&&!MusicPlayback.active()&&!playback[0].playing());
            await(()->fixture.stops>previousStops);assertNull(playback[0].queue.current());
            SystemClock.sleep(350);assertFalse("返回后不能被在途播放响应恢复",playback[0].playing());host=browser;
        }finally{close();}
    }
    @Test public void musicMenuScrollsLatestStaysOneRowAndUpOpensMainMenu()throws Exception{
        try(Fixture fixture=new Fixture()){
            setup(fixture);host=(MusicActivity)instrumentation.startActivitySync(new Intent(context,MusicActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            await(()->find(host.content,"最新专辑")!=null&&find(host.content,"最近常听")!=null);
            LinearLayout categories=(LinearLayout)field("musicMenu");View menu=(View)field("mainMenu");
            assertEquals(6,categories.getChildCount());String[] labels={"推荐","专辑","歌手","歌曲","文件夹","歌单"};
            for(int i=0;i<labels.length;i++)assertEquals(labels[i],((TextView)categories.getChildAt(i)).getText().toString());
            HorizontalScrollView latest=(HorizontalScrollView)host.content.getChildAt(1);GridLayout albums=(GridLayout)latest.getChildAt(0);
            assertEquals(1,albums.getRowCount());assertEquals(12,albums.getChildCount());
            main(()->{for(int i=1;i<albums.getChildCount();i++)assertEquals("最新专辑不能换行",albums.getChildAt(0).getTop(),albums.getChildAt(i).getTop());albums.getChildAt(0).requestFocus();});
            for(int i=0;i<8;i++)instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
            await(()->latest.getScrollX()>0);screenshot("music-latest-single-row");
            View first=categories.getChildAt(0);main(first::requestFocus);await(()->host.screenScroll.getScrollY()==0);
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_UP);await(()->menu.getVisibility()==View.VISIBLE);
            assertFalse("主菜单展开时不能与音乐分类叠在一起",categories.isShown());
            screenshot("music-main-menu");instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN);
            await(()->menu.getVisibility()==View.GONE&&first.hasFocus());
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_UP);await(()->menu.getVisibility()==View.VISIBLE);
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);await(()->menu.getVisibility()==View.GONE);assertFalse(host.isFinishing());
            GridLayout frequent=(GridLayout)host.content.getChildAt(host.content.getChildCount()-1);main(()->frequent.getChildAt(frequent.getChildCount()-1).requestFocus());
            await(()->host.screenScroll.getScrollY()>0);int[] position=new int[2];main(()->categories.getLocationOnScreen(position));assertTrue("音乐分类要随内容滚动",position[1]<0);
            screenshot("music-menu-scrolled");
        }finally{close();}
    }
    @Test public void musicNewestPagesLoadOnScrollAndRetryWithoutLosingFocus()throws Exception{
        try(Fixture fixture=new Fixture()){
            fixture.pagedMusic=true;setup(fixture);host=(MusicActivity)instrumentation.startActivitySync(new Intent(context,MusicActivity.class).putExtra("tab","albums").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            await(()->((GridLayout)fieldUnchecked("listing")).getChildCount()==60);SystemClock.sleep(300);
            assertEquals("首屏不能提前拉取整个库",1,fixture.musicRequests.size());assertTrue(fixture.musicRequests.get(0).contains("SortBy=DateCreated&SortOrder=Descending"));
            GridLayout grid=(GridLayout)field("listing");View anchor=grid.getChildAt(58);main(anchor::requestFocus);
            await(()->grid.getChildCount()==120);assertTrue("追加一页后保留原卡片焦点",anchor.hasFocus());
            main(()->grid.getChildAt(119).requestFocus());await(()->grid.getChildCount()==125);SystemClock.sleep(250);assertEquals(3,fixture.musicRequests.size());
            assertEquals("条目 125",find(grid.getChildAt(0),"条目 125").getText().toString());screenshot("music-infinite-scroll");
            fixture.failMusicStart=60;LinearLayout categories=(LinearLayout)field("musicMenu");main(()->categories.getChildAt(2).performClick());await(()->((GridLayout)fieldUnchecked("listing")).getChildCount()==60);
            assertTrue(fixture.musicRequests.get(3).contains("Tab=artists")&&fixture.musicRequests.get(3).contains("SortBy=DateCreated&SortOrder=Descending"));
            GridLayout artists=(GridLayout)field("listing");main(()->artists.getChildAt(59).requestFocus());await(()->((Button)fieldUnchecked("more")).isShown());
            int failedCount=fixture.musicRequests.size();SystemClock.sleep(300);assertEquals("失败不能形成自动请求循环",failedCount,fixture.musicRequests.size());
            main(()->((Button)fieldUnchecked("more")).performClick());await(()->artists.getChildCount()==120);assertTrue(artists.getChildAt(59).hasFocus());
            main(()->artists.getChildAt(119).requestFocus());await(()->artists.getChildCount()==125);
        }finally{close();}
    }
    @Test public void songsTabPaginatesTrackCardsAndPlaysSelectedSong()throws Exception{
        try(Fixture fixture=new Fixture()){
            fixture.pagedMusic=true;setup(fixture);host=(MusicActivity)instrumentation.startActivitySync(new Intent(context,MusicActivity.class).putExtra("parent","library-fixture").putExtra("tab","albums").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            await(()->((GridLayout)fieldUnchecked("listing")).getChildCount()==60);
            int albumColumns=((GridLayout)field("listing")).getColumnCount();LinearLayout categories=(LinearLayout)field("musicMenu");TextView songs=find(categories,"歌曲");assertNotNull("音乐库必须提供歌曲标签",songs);
            main(songs::requestFocus);instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER);
            await(()->find((GridLayout)fieldUnchecked("listing"),"歌曲 125")!=null);
            GridLayout grid=(GridLayout)field("listing");assertEquals(60,grid.getChildCount());assertTrue("歌曲使用紧凑横向卡片",grid.getColumnCount()<albumColumns);assertEquals(LinearLayout.HORIZONTAL,((LinearLayout)grid.getChildAt(0)).getOrientation());
            String path=fixture.musicRequests.get(fixture.musicRequests.size()-1);assertTrue(path.contains("Tab=songs")&&path.contains("ParentId=library-fixture")&&path.contains("SortBy=DateCreated&SortOrder=Descending"));
            View anchor=grid.getChildAt(58);main(anchor::requestFocus);await(()->grid.getChildCount()==120);assertTrue(anchor.hasFocus());
            main(()->grid.getChildAt(119).requestFocus());await(()->grid.getChildCount()==125);main(()->grid.getChildAt(0).requestFocus());screenshot("music-songs");
            TvActivity browser=host;Instrumentation.ActivityMonitor monitor=instrumentation.addMonitor(MusicPlayerActivity.class.getName(),null,false);
            main(()->grid.getChildAt(61).performClick());MusicPlayerActivity player=(MusicPlayerActivity)instrumentation.waitForMonitorWithTimeout(monitor,10000);instrumentation.removeMonitor(monitor);assertNotNull(player);host=player;
            await(()->MusicPlayback.active()&&MusicPlayback.get(context).playing());main(()->{MusicQueue queue=MusicPlayback.get(context).queue;assertEquals("item-64",queue.current().optString("Id"));assertEquals(125,queue.total);assertEquals(125,queue.items.size());assertTrue(queue.path.contains("Tab=songs")&&queue.path.contains("ParentId=library-fixture"));});
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);await(()->player.isFinishing()&&!MusicPlayback.active());host=browser;
        }finally{close();}
    }
    @Test public void nestedMusicFoldersKeepRootCardSizeAndSeparateSongs()throws Exception{
        List<TvActivity> opened=new ArrayList<>();
        try(Fixture fixture=new Fixture()){
            fixture.folderMusic=true;setup(fixture);host=(MusicActivity)instrumentation.startActivitySync(new Intent(context,MusicActivity.class).putExtra("tab","folders").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));opened.add(host);
            int width=0,height=0;
            for(int depth=0;depth<4;depth++){
                final int level=depth;await(()->find(host.content,"子目录 "+(level+1))!=null&&find(host.content,"歌曲 "+(level+1))!=null);
                View folder=(View)find(host.content,"子目录 "+(depth+1)).getParent().getParent();View artwork=((ViewGroup)folder).getChildAt(0);await(()->folder.getWidth()>0&&artwork.getHeight()==folder.getWidth()&&!folder.isLayoutRequested());
                if(depth==0){width=folder.getWidth();height=folder.getHeight();}else{assertEquals("进入子目录不能放大封面宽度",width,folder.getWidth(),1);assertEquals("进入子目录不能放大封面高度",height,folder.getHeight(),1);}
                if(depth==2)screenshot("music-nested-folders");
                if(depth<3){Instrumentation.ActivityMonitor monitor=instrumentation.addMonitor(MusicActivity.class.getName(),null,false);main(folder::performClick);TvActivity next=(TvActivity)instrumentation.waitForMonitorWithTimeout(monitor,10000);instrumentation.removeMonitor(monitor);assertNotNull(next);host=next;opened.add(host);}
            }
            GridLayout songs=(GridLayout)field("listing");assertEquals(1,songs.getChildCount());assertEquals(LinearLayout.HORIZONTAL,((LinearLayout)songs.getChildAt(0)).getOrientation());assertNotSame("目录和歌曲各用自己的卡片尺寸",songs,((View)find(host.content,"子目录 4").getParent().getParent()).getParent());
        }finally{for(TvActivity activity:opened)main(activity::finish);close();}
    }
    @Test public void lyricsUseLocalThenEmbeddedThenDownloadAndDiscardLateSong()throws Exception{
        try(Fixture fixture=new Fixture()){
            setup(fixture);List<JSONObject> tracks=Arrays.asList(fixture.track(1),fixture.track(2),fixture.track(3),fixture.track(4),fixture.track(5));MusicPlayback[] player={null};
            main(()->{player[0]=MusicPlayback.get(context);player[0].play(tracks,tracks.get(0),"",tracks.size());});
            host=(MusicPlayerActivity)instrumentation.startActivitySync(new Intent(context,MusicPlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            await(()->player[0].playing());main(()->player[0].toggle());Button toggle=(Button)field("lyricToggle");assertEquals("默认打开歌词",View.VISIBLE,((View)field("lyricPanel")).getVisibility());assertEquals(View.GONE,((ListView)field("queue")).getVisibility());assertEquals("显示播放列表",toggle.getContentDescription());
            await(()->find((View)fieldUnchecked("lyricPanel"),"本地 LRC")!=null);assertEquals(2,((MusicLyrics)field("lyricData")).lines.size());assertEquals(1,fixture.lyricRequests.size());assertTrue(fixture.lyricRequests.get(0).startsWith("GET "));assertTrue(fixture.lyricRequests.get(0).contains("MediaSourceId=version-1"));
            assertEquals(0,((Number)field("lyricIndex")).intValue());main(()->player[0].seek(3300));await(()->((Number)fieldUnchecked("lyricIndex")).intValue()==1);screenshot("music-lyrics-lrc");
            main(toggle::performClick);assertEquals(View.VISIBLE,((ListView)field("queue")).getVisibility());assertEquals(View.GONE,((View)field("lyricPanel")).getVisibility());main(()->{assertFalse(player[0].playing());assertTrue(player[0].position()>=3000);});
            main(toggle::performClick);await(()->find((View)fieldUnchecked("lyricPanel"),"本地 LRC")!=null);
            main(()->player[0].playAt(1));await(()->player[0].playing()&&find((View)fieldUnchecked("lyricPanel"),"内嵌歌词")!=null);main(()->player[0].toggle());assertFalse(((MusicLyrics)field("lyricData")).synced);assertFalse(fixture.lyricRequests.stream().anyMatch(value->value.startsWith("POST ")&&value.contains("item-2/")));
            main(()->player[0].playAt(2));await(()->player[0].playing()&&find((View)fieldUnchecked("lyricPanel"),"在线歌词")!=null);main(()->player[0].toggle());
            List<String> online=new ArrayList<>();for(String request:fixture.lyricRequests)if(request.contains("item-3/"))online.add(request);assertEquals(2,online.size());assertTrue(online.get(0).startsWith("GET "));assertTrue(online.get(1).startsWith("POST "));
            fixture.preferences.put("mode","light");await(()->!host.appearanceDark());main(()->assertIconButtons(host.root));screenshot("music-lyrics-light");
            main(()->player[0].playAt(3));await(()->find((View)fieldUnchecked("lyricPanel"),"歌词来源暂不可用，重新打开可重试")!=null);main(()->player[0].toggle());
            fixture.lyricFailure=false;main(toggle::performClick);main(toggle::performClick);await(()->find((View)fieldUnchecked("lyricPanel"),"重试歌词")!=null);
            main(()->player[0].playAt(4));assertTrue(fixture.lyricStarted.await(8,TimeUnit.SECONDS));main(()->player[0].playAt(0));fixture.releaseLyric.countDown();
            await(()->player[0].playing()&&find((View)fieldUnchecked("lyricPanel"),"本地 LRC")!=null);main(()->player[0].toggle());SystemClock.sleep(300);assertNull(find((View)field("lyricPanel"),"过期歌词"));
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);await(()->host.isFinishing()&&!MusicPlayback.active());
        }finally{close();}
    }
    @Test public void loginBrandUsesEtknLogoAndMediaServerName()throws Exception{
        try(Fixture fixture=new Fixture()){
            setup(fixture);host=(SetupActivity)instrumentation.startActivitySync(new Intent(context,SetupActivity.class).putExtra(SetupActivity.EXTRA_ADD_ACCOUNT,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            await(()->find(host.root,"音乐库测试服")!=null&&"#55c7b0".equals(TvAppearance.current.optString("theme_color")));main(this::assertBrandColor);
            fixture.preferences.put("theme_color","#bf395d");await(()->"#bf395d".equals(TvAppearance.current.optString("theme_color")));main(this::assertBrandColor);screenshot("login-brand-dark");
            fixture.preferences.put("mode","light");await(()->!host.appearanceDark());main(this::assertBrandColor);screenshot("login-brand-light");
            call("showPicker",new Class<?>[]{});await(()->find(host.root,"音乐库测试服")!=null);main(this::assertBrandColor);
        }finally{close();}
    }
    void assertBrandColor(){
        ViewGroup brand=(ViewGroup)host.root.getChildAt(0);assertTrue(brand.getChildAt(0) instanceof ImageView);ImageView logo=(ImageView)brand.getChildAt(0);assertNotNull(logo.getDrawable());assertEquals("ETKN",logo.getContentDescription());
        android.graphics.Bitmap frame=android.graphics.Bitmap.createBitmap(logo.getWidth(),logo.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
        try{logo.draw(new android.graphics.Canvas(frame));int pixel=0;for(int y=0;y<frame.getHeight();y++)for(int x=0;x<frame.getWidth();x++)if(android.graphics.Color.alpha(frame.getPixel(x,y))>android.graphics.Color.alpha(pixel))pixel=frame.getPixel(x,y);
            assertTrue("Logo必须实际绘制",android.graphics.Color.alpha(pixel)>200);assertEquals("Logo实际颜色必须跟随当前主题",host.resolveColor(TvActivity.ACCENT)&0xffffff,pixel&0xffffff);
            assertEquals("Logo内圈空白必须保持透明",0,android.graphics.Color.alpha(frame.getPixel(frame.getWidth()/2,frame.getHeight()/4)));
        }finally{frame.recycle();}
    }
    void assertIconButtons(View view){if(view instanceof Button){Button button=(Button)view;assertEquals("播放页按钮不显示文字","",button.getText().toString());assertNotNull(button.getCompoundDrawables()[0]);assertNotNull(button.getContentDescription());assertIconColor(button.getCompoundDrawables()[0]);}if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)assertIconButtons(group.getChildAt(i));}}
    void assertIconColor(android.graphics.drawable.Drawable drawable){
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(24,24,android.graphics.Bitmap.Config.ARGB_8888);Rect bounds=new Rect(drawable.getBounds());
        try{drawable.setBounds(0,0,24,24);drawable.draw(new android.graphics.Canvas(bitmap));int pixel=0;for(int y=0;y<24;y++)for(int x=0;x<24;x++)if(android.graphics.Color.alpha(bitmap.getPixel(x,y))>android.graphics.Color.alpha(pixel))pixel=bitmap.getPixel(x,y);
            assertTrue("图标必须有实际绘制像素",android.graphics.Color.alpha(pixel)>0);int expected=host.resolveColor(TvActivity.TEXT);
            for(int shift:new int[]{0,8,16})assertTrue("实际图标像素必须跟随当前明暗",Math.abs((pixel>>shift&255)-(expected>>shift&255))<=2);
        }finally{drawable.setBounds(bounds);bitmap.recycle();}
    }
    TextView find(View view,String text){if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return (TextView)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){TextView result=find(group.getChildAt(i),text);if(result!=null)return result;}}return null;}
    View findClass(View view,String name){if(view==null)return null;if(view.getClass().getSimpleName().equals(name))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View result=findClass(group.getChildAt(i),name);if(result!=null)return result;}}return null;}
    Object fieldUnchecked(String name){try{return field(name);}catch(Exception error){throw new RuntimeException(error);}}

    static final class Fixture implements AutoCloseable {
        final ServerSocket socket=new ServerSocket(0,20,InetAddress.getByName("127.0.0.1"));
        final ExecutorService workers=Executors.newCachedThreadPool();
        volatile boolean running=true,remember=true,albumRequested;volatile int audioRequests,completed,stops;volatile String lockedHeader="";
        final JSONObject preferences=new JSONObject("{\"revision\":\"fixture\",\"configured\":true,\"mode\":\"dark\",\"theme_color\":\"#55c7b0\",\"shadow_color\":\"#000000\",\"shadow_strength\":1,\"background_blur\":0,\"glass_opacity\":0.7,\"background_url\":\"\",\"card_size\":2,\"show_home_libraries\":true,\"home_carousel\":true,\"skip_mode\":0,\"playback_preferences\":{\"preferences\":{\"audio_lang_priority\":[\"jpn\"],\"subtitle_priority\":[\"chs\"]},\"global_preferences\":{},\"effective_preferences\":{\"audio_lang_priority\":[\"jpn\"],\"subtitle_priority\":[\"chs\"]},\"hide_watched_latest\":true}}");
        final byte[] audio=tone();
        volatile boolean pagedMusic,folderMusic,lyricFailure=true;
        volatile int failMusicStart=-1;
        final List<String> musicRequests=new CopyOnWriteArrayList<>(),lyricRequests=new CopyOnWriteArrayList<>();
        final CountDownLatch lyricStarted=new CountDownLatch(1),releaseLyric=new CountDownLatch(1);
        Fixture()throws Exception{workers.execute(()->{while(running)try{Socket client=socket.accept();workers.execute(()->serve(client));}catch(IOException ignored){}});}
        String origin(){return "http://127.0.0.1:"+socket.getLocalPort();}
        JSONObject track(int id)throws JSONException{return new JSONObject().put("Id","item-"+id).put("Type","Audio").put("Name","歌曲 "+id).put("Album","测试专辑").put("AlbumKey","album").put("Artists",new JSONArray().put("测试歌手")).put("RunTimeTicks",80000000);}
        void serve(Socket client){try(client){BufferedInputStream input=new BufferedInputStream(client.getInputStream());ByteArrayOutputStream headerBytes=new ByteArrayOutputStream();int current,tail=0;while((current=input.read())!=-1){headerBytes.write(current);tail=(tail<<8)|current;if(tail==0x0d0a0d0a)break;}
            String headers=headerBytes.toString(StandardCharsets.UTF_8.name()),first=headers.split("\r\n")[0];String[] request=first.split(" ");String method=request[0],path=request[1];int length=0;for(String header:headers.split("\r\n")){if(header.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(header.split(":",2)[1].trim());if(header.toLowerCase(Locale.ROOT).startsWith("x-etkn-locked-preferences:"))lockedHeader=header.split(":",2)[1].trim();}
            byte[] requestBody=new byte[length];new DataInputStream(input).readFully(requestBody);String body=new String(requestBody,StandardCharsets.UTF_8);JSONObject result=new JSONObject();byte[] data;String type="application/json";int status=200,start=0,end=audio.length-1;
            if(path.startsWith("/audio.wav")){audioRequests++;type="audio/wav";for(String header:headers.split("\r\n"))if(header.toLowerCase(Locale.ROOT).startsWith("range:")){String[] range=header.split("=",2)[1].trim().split("-",-1);start=Integer.parseInt(range[0]);if(!range[1].isEmpty())end=Math.min(end,Integer.parseInt(range[1]));status=206;}data=Arrays.copyOfRange(audio,start,end+1);}
            else{
                if(path.equals("/api/tv/System/Info/Public"))result.put("ProductName","ETKN").put("ServerName","音乐库测试服");
                else if(path.startsWith("/api/tv/Users/Me/ClientPreferences")){if(method.equals("PUT")){JSONObject update=new JSONObject(body);Iterator<String> keys=update.keys();while(keys.hasNext()){String key=keys.next();preferences.put(key,update.get(key));}}result=new JSONObject(preferences.toString());}
                else if(path.startsWith("/api/tv/Users/Me/AccountPreferences")){if(method.equals("PUT"))remember=new JSONObject(body).getBoolean("remember_admin_account");result.put("remember_admin_account",remember);}
                else if(path.equals("/api/tv/Users/Me"))result.put("Id","fixture").put("Name","音乐测试").put("Policy",new JSONObject().put("IsAdministrator",true));
                else if(path.contains("PlaybackInfo"))result.put("MediaSources",new JSONArray().put(new JSONObject().put("Id","version-1").put("DirectStreamUrl","/audio.wav").put("Container","wav"))).put("PlaySessionId",UUID.randomUUID().toString());
                else if(path.contains("/Sessions/Playing")){if(path.endsWith("/Stopped")){stops++;if(new JSONObject(body).optBoolean("PlayedToCompletion"))completed++;}}
                else if(path.contains("/Lyrics?")){
                    lyricRequests.add(method+" "+path);String text="";
                    if(path.contains("item-5/")){lyricStarted.countDown();releaseLyric.await(10,TimeUnit.SECONDS);text="[00:00]过期歌词";}
                    else if(path.contains("item-1/"))text="[00:00]本地 LRC\n[00:01]  \n[00:03]本地第二行";
                    else if(path.contains("item-2/"))text="内嵌歌词\n内嵌第二行";
                    else if(path.contains("item-4/")){if(lyricFailure){status=502;result.put("detail","歌词来源暂不可用");}else text="重试歌词";}
                    else if(method.equals("POST"))text="[00:00]在线歌词";
                    result.put("text",text);
                }
                else if(folderMusic&&path.contains("/Music?")){
                    java.util.regex.Matcher key=java.util.regex.Pattern.compile("Key=folder-([0-9]+)").matcher(path);int depth=key.find()?Integer.parseInt(key.group(1)):0;
                    result.put("Folders",new JSONArray().put(new JSONObject().put("Name","子目录 "+(depth+1)).put("Key","folder-"+(depth+1)).put("Kind","folders").put("Count",1))).put("Items",new JSONArray().put(track(depth+1))).put("TotalRecordCount",1);
                }
                else if(pagedMusic&&path.contains("/Music?")){
                    musicRequests.add(path);int offset=0;for(String part:path.split("&"))if(part.startsWith("StartIndex="))offset=Integer.parseInt(part.substring(11));
                    if(offset==failMusicStart){failMusicStart=-1;status=503;result.put("detail","分页暂不可用");}
                    else{JSONArray groups=new JSONArray();for(int i=offset;i<Math.min(125,offset+60);i++)groups.put(path.contains("Tab=songs")?track(125-i):new JSONObject().put("Name","条目 "+(125-i)).put("Key","group-"+i).put("Kind",path.contains("Tab=artists")?"artists":"albums").put("Count",4));result.put("Items",groups).put("TotalRecordCount",125);}
                }
                else if(path.contains("/Music?")){if(path.contains("Tab=recommended")){JSONArray latest=new JSONArray();for(int i=1;i<=12;i++)latest.put(new JSONObject().put("Name","最新专辑 "+i).put("Key","album-"+i).put("Kind","albums").put("Count",8));result.put("Latest",latest).put("Recent",new JSONArray().put(track(1))).put("Frequent",new JSONArray().put(track(2)));}else if(path.contains("Tab=albums")&&!path.contains("Key=album-")){result.put("Items",new JSONArray().put(new JSONObject().put("Id","album-1").put("Name","测试专辑").put("Kind","albums").put("Key","album-1").put("Count",3))).put("TotalRecordCount",1);}else if(path.contains("Tab=albums")&&path.contains("Key=album-1")){albumRequested=true;int offset=0;for(String part:path.split("&"))if(part.startsWith("StartIndex="))offset=Integer.parseInt(part.substring("StartIndex=".length()));JSONArray songs=new JSONArray();if(offset<2)songs.put(track(1)).put(track(2));else if(offset==2)songs.put(track(3));result.put("Items",songs).put("Title","测试专辑").put("TotalRecordCount",3);}else result.put("Items",new JSONArray().put(track(3))).put("TotalRecordCount",3);}
                else if(path.contains("/Images/")){status=404;}
                data=result.toString().getBytes(StandardCharsets.UTF_8);
            }
            String response="HTTP/1.1 "+status+" OK\r\nContent-Type: "+type+"\r\nContent-Length: "+data.length+"\r\nConnection: close\r\n"+(status==206?"Content-Range: bytes "+start+"-"+end+"/"+audio.length+"\r\n":"")+"\r\n";OutputStream output=client.getOutputStream();output.write(response.getBytes(StandardCharsets.UTF_8));output.write(data);output.flush();
        }catch(Exception ignored){}}
        static byte[] tone(){int count=16000*8;ByteBuffer wav=ByteBuffer.allocate(44+count*2).order(ByteOrder.LITTLE_ENDIAN);wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36+count*2).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(count*2);for(int i=0;i<count;i++)wav.putShort((short)(Math.sin(i*2*Math.PI*440/16000)*1200));return wav.array();}
        public void close()throws Exception{running=false;socket.close();workers.shutdownNow();}
    }
}
