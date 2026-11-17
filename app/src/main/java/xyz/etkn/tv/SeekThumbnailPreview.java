package xyz.etkn.tv;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Read existing seek images without adding work to playback startup. */
final class SeekThumbnailPreview {
    private final EtknApi api;
    private final FrameLayout stage;
    private final ImageView image;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final String user;
    private String item="",source="",imageKey="";
    private JSONObject manifest;
    private boolean requested,visible,closed,renderPending,imageLoading;
    private int generation;
    private long target,duration;
    private CancellationSignal cancellation;
    private final Runnable render=()->{renderPending=false;render();};

    SeekThumbnailPreview(EtknApi api,FrameLayout stage){
        this.api=api;this.stage=stage;user=api.session.userId();
        image=new ImageView(stage.getContext());image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setFocusable(false);image.setVisibility(View.GONE);
        GradientDrawable background=new GradientDrawable();background.setColor(Color.BLACK);
        background.setCornerRadius(dp(6));background.setStroke(1,Color.BLACK);
        image.setBackground(background);image.setClipToOutline(true);
        stage.addView(image,new FrameLayout.LayoutParams(dp(196),dp(110),Gravity.BOTTOM|Gravity.LEFT));
    }
    private int dp(int value){return Math.round(value*stage.getResources().getDisplayMetrics().density);}
    void show(String itemId,String sourceId,long position,long length){
        if(closed||sourceId.isEmpty()||length<=0||!TvAppearance.sameSession(api,user)){hide();return;}
        if(!item.equals(itemId)||!source.equals(sourceId)){reset();item=itemId;source=sourceId;}
        target=Math.max(0,Math.min(position,length));duration=length;visible=true;
        if(!requested)load();
        if(!renderPending){renderPending=true;main.postDelayed(render,75);}
    }
    private void load(){
        requested=true;int revision=generation;String expectedSource=source;
        cancellation=new CancellationSignal();CancellationSignal signal=cancellation;
        String path="/api/tv/Items/"+EtknApi.encode(item)+"/PreviewThumbnails?MediaSourceId="+EtknApi.encode(source);
        io.execute(()->{
            JSONObject result;try{result=api.get(path,signal);}catch(Exception ignored){result=null;}
            JSONObject value=result;
            main.post(()->{
                if(closed||revision!=generation||!TvAppearance.sameSession(api,user))return;
                cancellation=null;
                if(value!=null&&expectedSource.equals(value.optString("MediaSourceId")))manifest=value;
                if(visible)render();
            });
        });
    }
    private void render(){
        if(!visible||manifest==null||duration<=0)return;
        JSONObject frame=frameAt(manifest.optJSONArray("Thumbnails"),target);
        if(frame==null)return;
        int width=dp(196);
        double aspect=manifest.optDouble("AspectRatio",16d/9);
        if(!Double.isFinite(aspect)||aspect<.5||aspect>4)aspect=16d/9;
        FrameLayout.LayoutParams layout=(FrameLayout.LayoutParams)image.getLayoutParams();
        layout.width=width;layout.height=(int)Math.round(width/aspect);layout.bottomMargin=dp(76);
        int inset=dp(28),available=Math.max(0,stage.getWidth()-2*inset);
        layout.leftMargin=Math.max(inset,Math.min(stage.getWidth()-inset-width,inset+(int)(available*(double)target/duration)-width/2));
        image.setLayoutParams(layout);
        String key=frame.optString("ImageTag");
        if(key.equals(imageKey)||imageLoading)return;
        imageLoading=true;int revision=generation;
        imageKey=key;image.setTag(null);image.setImageDrawable(null);image.setVisibility(View.GONE);
        String url=api.server+"/api/tv/Items/"+EtknApi.encode(item)+"/PreviewThumbnails/"+frame.optInt("Index")+"?MediaSourceId="+EtknApi.encode(source)+"&tag="+EtknApi.encode(key);
        Images.thumbnail(api,url,image,320,(int)Math.round(320/aspect),loaded->{
            if(closed||revision!=generation||!TvAppearance.sameSession(api,user))return;
            imageLoading=false;
            JSONObject current=frameAt(manifest.optJSONArray("Thumbnails"),target);
            if(visible&&current!=null&&key.equals(current.optString("ImageTag")))image.setVisibility(loaded?View.VISIBLE:View.GONE);
            else if(visible)render();
        });
    }
    static JSONObject frameAt(JSONArray frames,long positionMs){
        if(frames==null||frames.length()==0)return null;
        long ticks=Math.max(0,positionMs)*10000;
        int left=0,right=frames.length()-1;
        while(left<right){int middle=(left+right)>>>1;if(frames.optJSONObject(middle).optLong("PositionTicks")<ticks)left=middle+1;else right=middle;}
        JSONObject next=frames.optJSONObject(left),previous=frames.optJSONObject(Math.max(0,left-1));
        return ticks-previous.optLong("PositionTicks")<=next.optLong("PositionTicks")-ticks?previous:next;
    }
    void hide(){
        generation++;visible=false;renderPending=false;imageLoading=false;main.removeCallbacks(render);imageKey="";image.setTag(null);image.setImageDrawable(null);image.setVisibility(View.GONE);
        if(cancellation!=null){generation++;cancellation.cancel();cancellation=null;requested=false;}
    }
    void reset(){hide();generation++;requested=false;manifest=null;}
    void close(){reset();closed=true;io.shutdownNow();main.removeCallbacksAndMessages(null);}
}
