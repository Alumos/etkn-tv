package xyz.etkn.tv;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class SeekThumbnailPreviewTest {
    @Test public void nearestFrameClampsAndWorksInBothDirections()throws Exception{
        JSONArray frames=new JSONArray();
        for(int i=0;i<3;i++)frames.put(new JSONObject().put("Index",i).put("PositionTicks",i*100000000L));
        assertEquals(0,SeekThumbnailPreview.frameAt(frames,-200).getInt("Index"));
        assertEquals(1,SeekThumbnailPreview.frameAt(frames,14000).getInt("Index"));
        assertEquals(2,SeekThumbnailPreview.frameAt(frames,16000).getInt("Index"));
        assertEquals(0,SeekThumbnailPreview.frameAt(frames,4000).getInt("Index"));
        assertEquals(2,SeekThumbnailPreview.frameAt(frames,999999).getInt("Index"));
        assertNull(SeekThumbnailPreview.frameAt(new JSONArray(),1000));
    }
}
