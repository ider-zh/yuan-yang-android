package com.yuanyang.camera;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

final class TimelineStore {
    private static final Pattern SHOT=Pattern.compile("[0-9a-f-]{36}\\.jpg");
    private final File root;
    TimelineStore(File files) { root=new File(files,"timeline"); }
    private File project(String project) {
        return new File(root,UUID.fromString(project).toString());
    }
    File newPhoto(String project) throws IOException {
        File dir=project(project);
        if(!dir.isDirectory()&&!dir.mkdirs()&&!dir.isDirectory()) throw new IOException("无法创建项目照片目录");
        return new File(dir,UUID.randomUUID().toString()+".jpg");
    }
    List<File> photos(String project) {
        File[] files=project(project).listFiles(f->f.isFile()&&SHOT.matcher(f.getName()).matches()&&f.length()>0);
        ArrayList<File> items=new ArrayList<>();
        if(files!=null) items.addAll(Arrays.asList(files));
        items.sort(Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
        return items;
    }
    static String day(File f) { return format(f,"yyyy-MM-dd"); }
    static String time(File f) { return format(f,"HH:mm:ss"); }
    private static String format(File f,String fmt) {
        return new SimpleDateFormat(fmt,Locale.ROOT).format(new Date(f.lastModified()));
    }
    static LinkedHashMap<String,List<File>> days(List<File> items) {
        LinkedHashMap<String,List<File>> result=new LinkedHashMap<>();
        for(File f:items) {
            String day=day(f);
            List<File> group=result.get(day);
            if(group==null) { group=new ArrayList<>();result.put(day,group); }
            group.add(f);
        }
        return result;
    }
    static File pick(List<File> group,String name) {
        if(name!=null) for(File f:group) if(name.equals(f.getName())) return f;
        return group.get(group.size()-1);
    }
    static List<File> select(LinkedHashMap<String,List<File>> grouped,Map<String,String> names) {
        List<File> frames=new ArrayList<>();
        for(Map.Entry<String,List<File>> e:grouped.entrySet()) frames.add(pick(e.getValue(),names.get(e.getKey())));
        return frames;
    }
}
