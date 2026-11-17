package xyz.etkn.tv;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** LRC and plain-text parsing follows the Web music player's timing rules. */
final class MusicLyrics {
    static final class Line {
        final long start;
        String text;
        Line(long start,String text){this.start=start;this.text=text;}
    }
    private static final String TIME="\\d{1,3}:[0-5]\\d(?:[.:]\\d{1,3})?";
    private static final Pattern PREFIX=Pattern.compile("^(?:\\["+TIME+"\\])+");
    private static final Pattern STAMP=Pattern.compile("\\[(\\d{1,3}):([0-5]\\d)(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern OFFSET=Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*\\]",Pattern.CASE_INSENSITIVE);
    final List<Line> lines;
    final boolean synced;
    private MusicLyrics(List<Line> lines,boolean synced){this.lines=lines;this.synced=synced;}
    static MusicLyrics parse(String text){
        List<Line> timed=new ArrayList<>(),plain=new ArrayList<>();long offset=0;
        Matcher offsets=OFFSET.matcher(text);while(offsets.find())try{offset=Long.parseLong(offsets.group(1));}catch(NumberFormatException ignored){}
        for(String raw:text.replace("\r","").split("\n")){
            Matcher prefix=PREFIX.matcher(raw);
            if(!prefix.find()){String value=raw.replaceFirst("(?i)^\\[[a-z][\\w-]*:[^\\]]*\\]","").trim();if(!value.isEmpty())plain.add(new Line(0,value));continue;}
            String content=raw.substring(prefix.end()).replaceAll("<"+TIME+">","").trim();if(content.isEmpty())continue;Matcher stamp=STAMP.matcher(prefix.group());
            while(stamp.find()){
                String fraction=stamp.group(3);long milliseconds=fraction==null?0:Long.parseLong((fraction+"00").substring(0,3));
                long start=Long.parseLong(stamp.group(1))*60000+Long.parseLong(stamp.group(2))*1000+milliseconds;
                timed.add(new Line(Math.max(0,start-offset),content));
            }
        }
        timed.sort(Comparator.comparingLong(line->line.start));List<Line> merged=new ArrayList<>();
        for(Line line:timed){Line previous=merged.isEmpty()?null:merged.get(merged.size()-1);if(previous!=null&&previous.start==line.start){if(!line.text.isEmpty()&&!line.text.equals(previous.text))previous.text=previous.text.isEmpty()?line.text:previous.text+" / "+line.text;}else merged.add(line);}
        return new MusicLyrics(merged.isEmpty()?plain:merged,!merged.isEmpty());
    }
    int current(long position){if(!synced)return -1;int low=0,high=lines.size();while(low<high){int middle=(low+high)>>>1;if(lines.get(middle).start<=position)low=middle+1;else high=middle;}return low-1;}
}
