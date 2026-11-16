package xyz.etkn.tv;

import androidx.media3.common.text.Cue;
import androidx.media3.ui.SubtitleView;
import io.github.peerless2012.ass.media.AssHandler;
import io.github.peerless2012.ass.AssRender;
import java.util.ArrayList;
import java.util.List;

/** Device-local size; bitmap display sets keep their layout and bottom edge. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class SubtitleSize {
    private SubtitleSize(){}
    static int clamp(int value){return Math.max(50,Math.min(200,value));}
    static void bindAss(SubtitleView view,AssHandler handler){
        view.setTag(R.id.subtitle_ass_handler,handler);
        kotlin.jvm.functions.Function1<AssRender,kotlin.Unit> original=handler.getRenderCallback();
        handler.setRenderCallback(render->{
            Object percent=view.getTag(R.id.subtitle_size_percent);if(render!=null)render.setFontScale(percent instanceof Integer?(Integer)percent/100f:1f);
            if(original!=null)original.invoke(render);return kotlin.Unit.INSTANCE;
        });
    }
    static void apply(SubtitleView view,int percent){
        if(view==null)return;view.setTag(R.id.subtitle_size_percent,clamp(percent));float scale=clamp(percent)/100f;
        view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION*scale);
        view.setApplyEmbeddedFontSizes(percent==100);
        Object handler=view.getTag(R.id.subtitle_ass_handler);
        AssRender render=handler instanceof AssHandler?((AssHandler)handler).getRender():null;if(render!=null)render.setFontScale(scale);
    }
    static List<Cue> cues(List<Cue> cues,int percent){
        if(percent==100)return cues;
        float left=1,top=1,right=0,bottom=0;
        for(Cue cue:cues)if(bitmap(cue)){
            float x=cue.position-anchor(cue.positionAnchor)*cue.size,y=cue.line-anchor(cue.lineAnchor)*cue.bitmapHeight;
            left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x+cue.size);bottom=Math.max(bottom,y+cue.bitmapHeight);
        }
        if(right<=left||bottom<=top)return cues;
        float scale=Math.min(clamp(percent)/100f,Math.min(1/(right-left),1/(bottom-top)));
        float center=(left+right)/2,half=(right-left)*scale/2;
        float shift=Math.max(half,Math.min(1-half,center))-center;
        float base=Math.max((bottom-top)*scale,Math.min(1,bottom));
        List<Cue> result=new ArrayList<>(cues.size());
        for(Cue cue:cues)result.add(bitmap(cue)?cue.buildUpon()
            .setPosition(center+(cue.position-center)*scale+shift)
            .setLine(base+(cue.line-bottom)*scale,Cue.LINE_TYPE_FRACTION)
            .setSize(cue.size*scale).setBitmapHeight(cue.bitmapHeight*scale).build():cue);
        return result;
    }
    private static boolean bitmap(Cue cue){return cue.bitmap!=null&&cue.lineType==Cue.LINE_TYPE_FRACTION&&cue.position!=Cue.DIMEN_UNSET&&cue.size>0&&cue.bitmapHeight>0;}
    private static float anchor(int anchor){return anchor==Cue.ANCHOR_TYPE_END?1:anchor==Cue.ANCHOR_TYPE_MIDDLE?.5f:0;}
}
