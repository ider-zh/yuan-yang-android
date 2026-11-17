package com.yuanyang.camera;
import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

public final class TimelineStoreTest {
    private static File write(File f,String when) throws Exception {
        Files.write(f.toPath(),new byte[]{1});
        if(!f.setLastModified(Instant.parse(when).toEpochMilli())) throw new AssertionError("Timestamp");
        return f;
    }
    private static void check(boolean yes,String label) {
        if(!yes) throw new AssertionError(label);
        System.out.println("PASS "+label);
    }
    public static void main(String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        File dir=Files.createTempDirectory(new File(args[0]).toPath(),"timeline-check-").toFile();
        TimelineStore store=new TimelineStore(dir);
        String a="12345678-1234-1234-1234-123456789abc",b="23456789-1234-1234-1234-123456789abc";
        File late=write(store.newPhoto(a),"2026-11-16T15:59:59Z");
        File earlier=write(store.newPhoto(a),"2026-11-16T07:01:00Z");
        File next=write(store.newPhoto(a),"2026-11-16T16:00:01Z");
        write(store.newPhoto(b),"2026-11-16T07:05:00Z");
        Files.write(new File(late.getParentFile(),"partial.pending").toPath(),new byte[]{1});
        Files.createFile(store.newPhoto(a).toPath());
        List<File> all=store.photos(a);
        check(all.equals(List.of(earlier,late,next)),"all records, ordered and isolated by project");
        var days=TimelineStore.days(all);
        check(days.size()==2 && days.get("2026-11-16").size()==2 && days.get("2026-11-17").size()==1,"local midnight separates dates");
        check(TimelineStore.select(days,Map.of()).equals(List.of(late,next)),"default: latest photo for each date");
        check(TimelineStore.select(days,Map.of("2026-11-16",earlier.getName())).equals(List.of(earlier,next)),"manual photo replaces default for its date");
        check(TimelineStore.select(days,Map.of("2026-11-16","unavailable.jpg")).equals(List.of(late,next)),"stale selection falls back without dropping the date");
    }
}
