package xyz.etkn.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class MusicLyricsTest {
    @Test public void lrcMatchesWebOffsetsRepeatedStampsAndTranslations(){
        MusicLyrics data=MusicLyrics.parse("[ar:歌手]\r\n[offset:100]\n[offset:+250]\n[00:01.5][01:02:003]你好<00:01.600>世界\n[00:01.500]Hello\n[00:03]\n");
        assertTrue(data.synced);assertEquals(2,data.lines.size());assertEquals(1250,data.lines.get(0).start);assertEquals("你好世界 / Hello",data.lines.get(0).text);assertEquals(61753,data.lines.get(1).start);
        assertEquals(-1,data.current(1249));assertEquals(0,data.current(1250));assertEquals(0,data.current(2750));assertEquals(1,data.current(100000));
    }
    @Test public void plainLyricsDiscardTagsAndCannotSeek(){
        MusicLyrics data=MusicLyrics.parse("[ti:歌曲]\n[ar:歌手]\n第一行\n\n 第二行 \n");
        assertFalse(data.synced);assertEquals(2,data.lines.size());assertEquals("第二行",data.lines.get(1).text);assertEquals(-1,data.current(5000));assertTrue(MusicLyrics.parse("").lines.isEmpty());
    }
    @Test public void blankTimedLinesDoNotCreateRowsOrHidePlainLyrics(){
        MusicLyrics empty=MusicLyrics.parse("[00:00]\n[00:01]  \n[00:02]<00:02.050> \n");
        assertTrue(empty.lines.isEmpty());assertFalse(empty.synced);
        MusicLyrics plain=MusicLyrics.parse("[00:00] \n普通歌词\n\n[00:03]\n");
        assertFalse(plain.synced);assertEquals(1,plain.lines.size());assertEquals("普通歌词",plain.lines.get(0).text);
    }
    @Test public void negativeTimeClampsAndTimedLyricsTakePriority(){
        MusicLyrics data=MusicLyrics.parse("未同步的额外说明\n[offset:2000]\n[00:00.2]开头\n[00:01]下一行\n[00:01]下一行\n[00:04.01]结尾");
        assertTrue(data.synced);assertEquals(2,data.lines.size());assertEquals("开头 / 下一行 / 下一行",data.lines.get(0).text);assertEquals(2010,data.lines.get(1).start);
    }
}
