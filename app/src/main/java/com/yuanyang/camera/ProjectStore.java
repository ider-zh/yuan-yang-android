package com.yuanyang.camera;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class ProjectStore {
    private final AtomicFile index;
    private final File photos;
    final JSONArray spaces;
    ProjectStore(Context c) throws IOException, JSONException {
        index = new AtomicFile(new File(c.getFilesDir(), "spaces.json"));
        photos = new File(c.getFilesDir(), "photos");
        if (!photos.isDirectory() && !photos.mkdirs()) throw new IOException("无法创建照片目录");
        byte[] bytes;
        try { bytes = index.readFully(); }
        catch (java.io.FileNotFoundException ex) {
            if (index.getBaseFile().exists()) throw ex;
            bytes = null;
        }
        spaces = bytes == null ? new JSONArray() : new JSONArray(new String(bytes, StandardCharsets.UTF_8));
    }
    void save() throws IOException {
        FileOutputStream out = index.startWrite();
        try {
            out.write(spaces.toString().getBytes(StandardCharsets.UTF_8));
            index.finishWrite(out);
        } catch (IOException e) {
            index.failWrite(out);
            throw e;
        }
    }
    static String id() { return UUID.randomUUID().toString(); }
    JSONObject makeAlbum(String name) throws JSONException, IOException {
        // Retain the existing on-disk schema for older albums and their original photos.
        JSONObject space = new JSONObject().put("id", id()).put("name", name)
            .put("points", new JSONArray()).put("rounds", new JSONArray());
        spaces.put(space);
        try { save(); } catch (IOException e) { spaces.remove(spaces.length()-1); throw e; }
        return space;
    }
    JSONObject getSpace(String id) {
        for (int i=0; i<spaces.length(); i++) {
            JSONObject s=spaces.optJSONObject(i);
            if (s!=null && s.optString("id").equals(id)) return s;
        }
        return null;
    }
    File photo(JSONObject point, JSONObject round) {
        return new File(photos, point.optString("id")+(round==null ? "-baseline" : "-"+round.optString("id"))+".jpg");
    }
    boolean exists(File f) { return f.isFile() && f.length() > 0; }
}
