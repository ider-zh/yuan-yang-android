package com.yuanyang.camera;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private static final int BG=0xfff2f5ee, CARD=0xffffffff, INK=0xff172e25, MUTED=0xff476056,
        LINE=0xffe0e9df, DARK=0xff18382d, LIME=0xffd2e9bb, TINT=0xffe4f0e0;
    private static final int TIMELINE_CAPTURE=42, EXPORT_PREVIEW=44;
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static final ExecutorService PREVIEWS=Executors.newSingleThreadExecutor();
    private static final ThreadPoolExecutor THUMBNAILS=new ThreadPoolExecutor(2,2,20,TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(48),new ThreadPoolExecutor.DiscardOldestPolicy());
    private static final LruCache<String,Bitmap> THUMB_CACHE=new LruCache<String,Bitmap>(8*1024*1024) {
        @Override protected int sizeOf(String key,Bitmap image){return image.getAllocationByteCount();}
    };
    private ProjectStore store;
    private TimelineStore timeline;
    private SharedPreferences settings;
    private boolean allFrames, browsingProjects, listFromAlbum, isForeground;
    private String spaceId;
    private boolean exporting, timelineExporting;
    private int exportGeneration, exportPercent, cameraInFlight;
    private Future<?> exportTask;
    private String resultProject;
    private File resultFile;
    private boolean resultVideo;
    private TextView progressLabel;
    private ProgressBar progressBar;
    private LinearLayout screen;
    private ListView albumList;
    private ActionMode albumSelectionMode;
    private String visibleAlbum;
    private final Map<String,int[]> scrollByAlbum=new HashMap<>();
    private String resultAlignment;
    private boolean pendingPreview, previewInFlight;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(DARK);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        if(state!=null) {
            spaceId=state.getString("space");
            allFrames=state.getBoolean("allFrames");
            pendingPreview=state.getBoolean("pendingPreview");
            previewInFlight=state.getBoolean("previewInFlight");
            browsingProjects=state.getBoolean("browsingProjects");
            listFromAlbum=state.getBoolean("listFromAlbum");
            resultProject=state.getString("resultProject");
            String path=state.getString("resultFile");
            if(path!=null) resultFile=new File(path);
            resultVideo=state.getBoolean("resultVideo");
            resultAlignment=state.getString("resultAlignment");
            cameraInFlight=state.getInt("cameraInFlight");
            Bundle positions=state.getBundle("albumPositions");
            if(positions!=null)for(String key:positions.keySet()) {
                int[] value=positions.getIntArray(key);
                if(value!=null && value.length==2)scrollByAlbum.put(key,value);
            }
        }
        try {
            store=new ProjectStore(this);
            timeline=new TimelineStore(getFilesDir());
            settings=getSharedPreferences("capture-settings",MODE_PRIVATE);
            if(state==null) openLastProject();
        }
        catch(Exception e) { screenBase(); heading("无法打开本地记录","没有覆盖原有数据。请重启应用后重试。"); return; }
        if(state==null && spaceId!=null) {
            screenBase();
            add(screen,text("正在打开相机…",18,MUTED,false),16);
            if(!captureTimeline()) render();
        } else render();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if(store==null || cameraInFlight!=0 || previewInFlight || pendingPreview ||
                !Intent.ACTION_MAIN.equals(intent.getAction())) return;
        try {
            openLastProject();
            if(!captureTimeline()) render();
        } catch(Exception e) {notice("无法打开上次使用的相册");}
    }
    private void openLastProject() throws Exception {
        JSONObject project=store.getSpace(settings.getString("last_project",null));
        if(project==null) project=store.spaces.optJSONObject(0);
        if(project==null) project=store.makeAlbum("默认相册");
        setCurrentProject(project);
    }
    private void setCurrentProject(JSONObject project) {
        spaceId=project.optString("id");
        allFrames=false; browsingProjects=false; listFromAlbum=false;
        settings.edit().putString("last_project",spaceId).apply();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        rememberGalleryScroll();
        Bundle positions=new Bundle();
        for(Map.Entry<String,int[]> item:scrollByAlbum.entrySet())positions.putIntArray(item.getKey(),item.getValue());
        out.putBundle("albumPositions",positions);
        out.putString("space",spaceId);
        out.putBoolean("allFrames",allFrames);
        out.putBoolean("pendingPreview",pendingPreview);
        out.putBoolean("previewInFlight",previewInFlight);
        out.putBoolean("browsingProjects",browsingProjects);
        out.putBoolean("listFromAlbum",listFromAlbum);
        out.putString("resultProject",resultProject);
        out.putString("resultFile",resultFile==null?null:resultFile.getPath());
        out.putBoolean("resultVideo",resultVideo);
        out.putString("resultAlignment",resultAlignment);
        out.putInt("cameraInFlight",cameraInFlight);
    }
    @Override protected void onResume() {
        super.onResume();
        isForeground=true;
        if(pendingPreview && resultProject!=null && resultProject.equals(spaceId) && !browsingProjects) {
            presentExport(resultFile,resultVideo);
        }
    }
    @Override protected void onPause() { isForeground=false; super.onPause(); }
    @Override protected void onDestroy() {
        exportGeneration++;
        if(exportTask!=null)exportTask.cancel(true);
        super.onDestroy();
    }
    private void showProjectList() { browsingProjects=true; listFromAlbum=true; render(); }
    @Override public void onBackPressed() {
        if(albumSelectionMode!=null) {albumSelectionMode.finish();return;}
        if(timelineExporting) {cancelTimelineExport();return;}
        if(cameraInFlight!=0) return;
        if(browsingProjects && listFromAlbum) {
            browsingProjects=false;
            listFromAlbum=false;
            render();
            return;
        }
        if(spaceId!=null) {
            captureTimeline(); // If camera launch fails, keep the current screen available.
            return;
        }
        finish();
    }
    @Override protected void onActivityResult(int req,int result,Intent data) {
        super.onActivityResult(req,result,data);
        if(req==cameraInFlight) cameraInFlight=0;
        if(req==EXPORT_PREVIEW) { previewInFlight=false; return; }
        if(req==TIMELINE_CAPTURE && store!=null) {
            if(result==RESULT_OK && data!=null && data.getBooleanExtra(CaptureActivity.RESULT_EXIT_APP,false)) {
                finish();
                return;
            }
            listFromAlbum=false;
            browsingProjects=result==RESULT_OK && data!=null
                && data.getBooleanExtra(CaptureActivity.RESULT_OPEN_ALBUM_LIST,false);
            render();
        }
    }
    private void render() {
        if(albumSelectionMode!=null)albumSelectionMode.finish();
        rememberGalleryScroll();albumList=null;visibleAlbum=null;
        progressLabel=null;progressBar=null;
        JSONObject space=store.getSpace(spaceId);
        if(browsingProjects || space==null) home();
        else projectTimeline(space);
    }
    private void screenBase() {
        progressLabel=null; progressBar=null;
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(BG);
        screen=stack();
        screen.setPadding(dp(20),dp(18),dp(20),dp(28));
        scroll.addView(screen,new ScrollView.LayoutParams(-1,-2));
        setContentView(scroll);
    }
    private void home() {
        makeGalleryBase(null);
        LinearLayout top=row();top.setGravity(Gravity.CENTER_VERTICAL);
        if(spaceId!=null)top.addView(action(listFromAlbum?"‹  返回相册":"‹  返回拍摄",TINT,DARK,v->onBackPressed()),
                new LinearLayout.LayoutParams(-2,dp(48)));
        TextView title=text("所有相册",19,INK,true);
        title.setGravity(Gravity.CENTER);
        top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        top.addView(action("新建相册",DARK,Color.WHITE,v->createAlbum()),new LinearLayout.LayoutParams(-2,dp(48)));
        add(screen,top,0);

        ArrayList<AlbumRow> albums=new ArrayList<>();
        for(int i=0;i<store.spaces.length();i++) {
            JSONObject entry=store.spaces.optJSONObject(i);
            if(entry==null)continue;
            List<File> files=projectPhotos(entry);
            albums.add(new AlbumRow(entry,files.size(),files.isEmpty()?null:files.get(files.size()-1)));
        }
        mountList(new BaseAdapter() {
            @Override public int getCount() {return albums.size();}
            @Override public Object getItem(int p) {return albums.get(p);}
            @Override public long getItemId(int p) {return p;}
            @Override public boolean areAllItemsEnabled(){return false;}
            @Override public boolean isEnabled(int p){return false;}
            @Override public View getView(int p,View convert,ViewGroup parent) {
                AlbumTile tile=convert==null?new AlbumTile():(AlbumTile)convert;
                tile.bind(albums.get(p));
                return tile;
            }
        });
    }
    private static final class AlbumRow {
        final JSONObject entry;
        final int count;
        final File cover;
        AlbumRow(JSONObject entry,int count,File cover){this.entry=entry;this.count=count;this.cover=cover;}
    }
    private final class AlbumTile extends LinearLayout {
        final ImageView cover;
        final TextView label, detail;
        AlbumTile() {
            super(MainActivity.this);
            setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(9),dp(7),dp(9),dp(7));
            setBackground(new RippleDrawable(ColorStateList.valueOf(0x18306955),rounded(CARD,13),null));
            setFocusable(true);
            cover=thumbnail();cover.setBackground(rounded(TINT,8));cover.setClipToOutline(true);
            cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(cover,new LinearLayout.LayoutParams(dp(56),dp(56)));
            LinearLayout name=stack();LinearLayout.LayoutParams grow=new LinearLayout.LayoutParams(0,-2,1);
            grow.leftMargin=dp(12);addView(name,grow);
            label=text("",16,INK,true);label.setMaxLines(2);add(name,label,0);
            detail=text("",13,MUTED,false);add(name,detail,3);
            TextView arrow=text("›",22,MUTED,true);
            arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(arrow,new LinearLayout.LayoutParams(-2,-2));
        }
        void bind(AlbumRow row) {
            String name=albumName(row.entry);
            label.setText(name);detail.setText(row.count+" 张照片");bindThumbnail(cover,row.cover);
            setContentDescription(name+"，"+row.count+" 张照片，打开相机");
            setOnClickListener(v->{setCurrentProject(row.entry);if(!captureTimeline())render();});
        }
    }

    private void makeGalleryBase(String key) {
        visibleAlbum=key==null?"all":key;
        screen=stack();
        screen.setBackgroundColor(BG);
        screen.setPadding(dp(14),dp(8),dp(14),0);
        setContentView(screen);
    }
    private void mountList(BaseAdapter adapter) {
        albumList=new ListView(this);
        albumList.setItemsCanFocus(true);
        albumList.setDivider(null);
        albumList.setSelector(android.R.color.transparent);
        albumList.setClipToPadding(false);
        albumList.setPadding(0,dp(8),0,dp(16));
        albumList.setAdapter(adapter);
        screen.addView(albumList,new LinearLayout.LayoutParams(-1,0,1));
        int[] pos=scrollByAlbum.get(visibleAlbum);
        if(pos!=null&&adapter.getCount()>0)
            albumList.setSelectionFromTop(Math.min(pos[0],adapter.getCount()-1),pos[1]);
    }
    private void rememberGalleryScroll() {
        if(albumList!=null && visibleAlbum!=null && albumList.getChildCount()>0)
            scrollByAlbum.put(visibleAlbum,new int[]{albumList.getFirstVisiblePosition(),
                    albumList.getChildAt(0).getTop()-albumList.getPaddingTop()});
    }

    private static final class ThumbRequest {
        final String key;
        Future<?> work;
        ThumbRequest(String key){this.key=key;}
    }
    private ImageView thumbnail() {
        ImageView image=new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return image;
    }
    private void bindThumbnail(ImageView image,File file) {
        String key=file==null?null:file.getAbsolutePath()+":"+file.lastModified();
        Object previous=image.getTag();
        if(previous instanceof ThumbRequest) {
            ThumbRequest old=(ThumbRequest)previous;
            if(old.work!=null && old.key.equals(key))return;
            if(old.work!=null){old.work.cancel(true);THUMBNAILS.remove((Runnable)old.work);}
        }
        image.setTag(null);
        image.setImageDrawable(null);
        if(key==null)return;
        ThumbRequest request=new ThumbRequest(key);
        image.setTag(request);
        Bitmap cached=THUMB_CACHE.get(key);
        if(cached!=null){image.setImageBitmap(cached);return;}
        WeakReference<ImageView> target=new WeakReference<>(image);
        request.work=THUMBNAILS.submit(()->{
            Bitmap bitmap=THUMB_CACHE.get(key);
            if(bitmap==null) {
                try{bitmap=PhotoTools.load(file,252);}catch(RuntimeException|OutOfMemoryError ignored){}
                if(bitmap!=null)THUMB_CACHE.put(key,bitmap);
            }
            Bitmap result=bitmap;
            new Handler(Looper.getMainLooper()).post(()->{
                ImageView destination=target.get();
                if(destination!=null&&destination.getTag()==request) {
                    destination.setImageBitmap(result);
                    request.work=null;
                }
            });
        });
    }

    private static void enabled(View view,boolean enable){view.setEnabled(enable);view.setAlpha(enable?1f:.55f);}
    private static String albumName(JSONObject album) {
        // Display the old autogenerated default in the new vocabulary without moving any files.
        String name=album.optString("name");
        return "默认项目".equals(name)?"默认相册":name;
    }
    private void createAlbum() {
        input("新建相册","例如：阳台的花、我的工作台","",name->{
            JSONObject s=store.makeAlbum(name);
            setCurrentProject(s);
            if(!captureTimeline()) render();
        });
    }

    private boolean captureTimeline() {
        if(cameraInFlight!=0)return false;
        if(exporting) { notice("正在导出，请稍候"); return false; }
        JSONObject project=store.getSpace(spaceId);
        if(project==null) return false;
        try {
            List<File> files=projectPhotos(project);
            File target=timeline.newPhoto(spaceId);
            Intent it=new Intent(this,CaptureActivity.class).putExtra(CaptureActivity.EXTRA_PATH,target.getAbsolutePath())
                .putExtra(CaptureActivity.EXTRA_PROJECT,albumName(project))
                .putExtra(CaptureActivity.EXTRA_ALBUM_ID,spaceId);
            if(!files.isEmpty()) it.putExtra(CaptureActivity.EXTRA_REFERENCE,files.get(files.size()-1).getAbsolutePath());
            cameraInFlight=TIMELINE_CAPTURE;
            startActivityForResult(it,TIMELINE_CAPTURE);return true;
        } catch(Exception e) {cameraInFlight=0;notice("无法打开相机，请检查存储空间");return false;}
    }
    private List<File> projectPhotos(JSONObject project) {
        ArrayList<File> list=new ArrayList<>(timeline.photos(project.optString("id")));
        JSONArray points=project.optJSONArray("points"), rounds=project.optJSONArray("rounds");
        for(int i=0;i<points.length();i++) {
            JSONObject point=points.optJSONObject(i);
            if(point==null) continue;
            File baseline=store.photo(point,null);
            if(store.exists(baseline)) list.add(baseline);
            for(int j=0;j<rounds.length();j++) {
                JSONObject round=rounds.optJSONObject(j);
                if(round==null) continue;
                File shot=store.photo(point,round);
                if(store.exists(shot)) list.add(shot);
            }
        }
        list.sort(Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
        return list;
    }
    private String dayKey(String day) { return "picked_"+spaceId+"_"+day; }
    private List<File> chosenFrames(LinkedHashMap<String,List<File>> days) {
        Map<String,String> picked=new HashMap<>();
        for(String day:days.keySet()) picked.put(day,settings.getString(dayKey(day),null));
        return TimelineStore.select(days,picked);
    }
    private void projectTimeline(JSONObject space) {
        makeGalleryBase(spaceId);
        LinearLayout top=row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(action("所有相册",TINT,DARK,v->showProjectList()),new LinearLayout.LayoutParams(-2,dp(48)));
        TextView title=text(albumName(space),18,INK,true);
        title.setGravity(Gravity.CENTER);title.setMaxLines(2);
        top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        top.addView(action("继续拍摄",DARK,Color.WHITE,v->captureTimeline()),new LinearLayout.LayoutParams(-2,dp(48)));
        add(screen,top,0);

        List<File> photos=projectPhotos(space);
        LinkedHashMap<String,List<File>> days=TimelineStore.days(photos);
        ArrayList<File> newest=new ArrayList<>(photos);
        Collections.reverse(newest);
        GalleryAdapter adapter=new GalleryAdapter(days,newest);
        LinearLayout strip=row();
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.addView(text(photos.size()+" 张照片  ·  "+days.size()+" 天",14,MUTED,false),
                new LinearLayout.LayoutParams(0,-2,1));
        TextView select=action("选择",TINT,DARK,v->adapter.startSelection(null));
        enabled(select,!photos.isEmpty()&&!exporting);
        strip.addView(select,new LinearLayout.LayoutParams(-2,dp(48)));
        TextView create=action("制作影像",LIME,DARK,v->{
            if(albumSelectionMode!=null)albumSelectionMode.finish();
            showExportSheet(photos,days);
        });
        create.setEnabled(!photos.isEmpty()&&!exporting);
        create.setAlpha(create.isEnabled()?1f:.48f);
        LinearLayout.LayoutParams createParams=new LinearLayout.LayoutParams(-2,dp(48));
        createParams.setMarginStart(dp(6));
        strip.addView(create,createParams);
        add(screen,strip,8);
        if(timelineExporting) {
            LinearLayout card=panel(DARK,16);
            progressLabel=text("",13,0xffe8f5e8,true);
            add(card,progressLabel,0);
            progressBar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
            progressBar.setMax(100); progressBar.setContentDescription("影像导出进度");
            progressBar.setProgressTintList(ColorStateList.valueOf(LIME));
            add(card,progressBar,4);
            updateProgress(exportPercent);
            add(card,action("取消导出",TINT,DARK,v->cancelTimelineExport()),6);
            add(screen,card,6);
        } else if(resultFile!=null && resultFile.isFile() && spaceId.equals(resultProject)) {
            add(screen,action("查看上次导出结果",TINT,DARK,v->presentExport(resultFile,resultVideo)),6);
        }
        if(photos.isEmpty()) {
            TextView empty=text("这个相册还没有照片。拍下第一张，就从这里开始。",15,MUTED,false);
            empty.setGravity(Gravity.CENTER);
            screen.addView(empty,new LinearLayout.LayoutParams(-1,0,1));
            return;
        }
        mountList(adapter);
    }

    private static final class GridRow {
        final String date;
        final int start, end, total;
        GridRow(String date,int start,int end,int total) {
            this.date=date;this.start=start;this.end=end;this.total=total;
        }
        boolean isHeader() {return start<0;}
    }

    private final class GalleryAdapter extends BaseAdapter {
        private final Map<String,List<File>> days;
        private final List<File> newest;
        private final LinkedHashSet<File> selected=new LinkedHashSet<>();
        private final String albumId=spaceId;
        private final ArrayList<GridRow> rows=new ArrayList<>();
        GalleryAdapter(LinkedHashMap<String,List<File>> days,List<File> newest) {
            this.days=days;this.newest=newest;
            ArrayList<String> keys=new ArrayList<>(days.keySet());
            Collections.reverse(keys);
            int start=0;
            for(String key:keys) {
                int size=days.get(key).size();
                rows.add(new GridRow(key,-1,-1,size));
                for(int x=start;x<start+size;x+=3)
                    rows.add(new GridRow(key,x,Math.min(x+3,start+size),size));
                start+=size;
            }
        }
        @Override public int getCount() {return rows.size();}
        @Override public Object getItem(int p) {return rows.get(p);}
        @Override public long getItemId(int p) {return p;}
        @Override public int getViewTypeCount() {return 2;}
        @Override public int getItemViewType(int p) {return rows.get(p).isHeader()?0:1;}
        @Override public boolean areAllItemsEnabled() {return false;}
        @Override public boolean isEnabled(int p) {return false;}
        @Override public View getView(int p,View convert,ViewGroup parent) {
            GridRow data=rows.get(p);
            if(data.isHeader()) {
                TextView label=convert==null?text("",16,INK,true):(TextView)convert;
                label.setText(data.date+"  ·  "+data.total+" 张");
                label.setPadding(0,dp(14),0,dp(7));
                if(android.os.Build.VERSION.SDK_INT>=28)label.setAccessibilityHeading(true);
                return label;
            }
            LinearLayout container;
            if(convert==null) {
                container=row();
                container.setPadding(0,0,0,dp(5));
                for(int i=0;i<3;i++) {
                    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);
                    lp.leftMargin=i==0?0:dp(5);
                    container.addView(new GalleryTile(),lp);
                }
            } else container=(LinearLayout)convert;
            File picked=TimelineStore.pick(days.get(data.date),settings.getString(dayKey(data.date),null));
            for(int i=0;i<3;i++) {
                GalleryTile tile=(GalleryTile)container.getChildAt(i);
                int index=data.start+i;
                File file=index<data.end?newest.get(index):null;
                tile.bind(file,picked,data.date,albumSelectionMode!=null,selected.contains(file),
                        ()->{if(albumSelectionMode!=null)toggle(file);else viewAlbumPhoto(newest,index,days,this);},
                        ()->toggle(file));
            }
            return container;
        }
        private void toggle(File file) {
            if(file==null)return;
            if(exporting){notice("请先完成或取消导出");return;}
            if(albumSelectionMode==null){startSelection(file);return;}
            if(!selected.remove(file))selected.add(file);
            refreshSelection();
        }
        private void startSelection(File file) {
            if(albumSelectionMode!=null || newest.isEmpty() || exporting)return;
            if(file!=null)selected.add(file);
            albumSelectionMode=startActionMode(new ActionMode.Callback(){
                @Override public boolean onCreateActionMode(ActionMode mode,Menu menu) {
                    menu.add(0,1,0,"全选").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                    menu.add(0,2,1,"删除").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                    return true;
                }
                @Override public boolean onPrepareActionMode(ActionMode mode,Menu menu) {
                    menu.findItem(1).setTitle(selected.size()==newest.size()?"取消全选":"全选");
                    menu.findItem(2).setEnabled(!selected.isEmpty());
                    return true;
                }
                @Override public boolean onActionItemClicked(ActionMode mode,MenuItem item) {
                    if(item.getItemId()==1) {
                        if(selected.size()==newest.size())selected.clear();
                        else selected.addAll(newest);
                        refreshSelection();
                        return true;
                    }
                    if(item.getItemId()==2) {
                        confirmDeletePhotos(albumId,new ArrayList<>(selected),null);
                        return true;
                    }
                    return false;
                }
                @Override public void onDestroyActionMode(ActionMode mode) {
                    albumSelectionMode=null;
                    selected.clear();
                    notifyDataSetChanged();
                }
            });
            if(albumSelectionMode==null)selected.clear();
            refreshSelection();
        }
        private void refreshSelection() {
            if(albumSelectionMode!=null) {
                albumSelectionMode.setTitle("已选 "+selected.size()+" 张");
                albumSelectionMode.invalidate();
            }
            notifyDataSetChanged();
        }
    }

    private final class GalleryTile extends FrameLayout {
        final ImageView photo;
        final TextView badge, time, selection;
        GalleryTile() {
            super(MainActivity.this);
            setBackground(rounded(TINT,10));
            setClipToOutline(true);
            setFocusable(true);
            photo=thumbnail();
            photo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(photo,new FrameLayout.LayoutParams(-1,-1));
            badge=text("每日选片",10,DARK,true);
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(dp(5),dp(2),dp(5),dp(2));
            badge.setBackground(rounded(LIME,6));
            badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams top=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.START);
            top.setMargins(dp(4),dp(4),0,0);addView(badge,top);
            time=text("",11,Color.WHITE,true);
            time.setPadding(dp(4),dp(2),dp(4),dp(2));
            time.setBackground(rounded(0xb0172e25,6));
            time.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams bottom=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.END);
            bottom.setMargins(0,0,dp(4),dp(4));addView(time,bottom);
            selection=text("",16,Color.WHITE,true);
            selection.setGravity(Gravity.CENTER);
            selection.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams check=new FrameLayout.LayoutParams(dp(27),dp(27),Gravity.TOP|Gravity.END);
            check.setMargins(0,dp(4),dp(4),0);addView(selection,check);
            setForeground(new RippleDrawable(ColorStateList.valueOf(0x45ffffff),null,
                    rounded(Color.WHITE,10)));
        }
        @Override protected void onMeasure(int w,int h) {
            int width=View.MeasureSpec.getSize(w);
            super.onMeasure(w,View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY));
        }
        void bind(File file,File daily,String date,boolean choosing,boolean checked,Runnable open,Runnable choose) {
            bindThumbnail(photo,file);
            if(file==null) {
                setVisibility(View.INVISIBLE);
                setOnClickListener(null);
                setOnLongClickListener(null);
                setContentDescription(null);
                return;
            }
            setVisibility(View.VISIBLE);
            boolean picked=file.equals(daily);
            setSelected(checked);
            selection.setVisibility(choosing?View.VISIBLE:View.GONE);
            selection.setText(checked?"✓":"○");
            selection.setTextColor(checked?DARK:Color.WHITE);
            selection.setBackground(rounded(checked?LIME:0xc0172e25,16));
            badge.setVisibility(picked?View.VISIBLE:View.GONE);
            String ts=TimelineStore.time(file);
            time.setText(ts);
            setContentDescription(date+" "+ts+(picked?"，每日选片":"")
                    +(choosing?(checked?"，已选中，点按取消":"，未选中，点按选择"):"，查看照片，长按多选"));
            setOnClickListener(v->open.run());
            setOnLongClickListener(v->{if(!choosing)choose.run();return true;});
        }
    }

    private void viewAlbumPhoto(List<File> newest,int selected,Map<String,List<File>> days,GalleryAdapter adapter) {
        Dialog dialog=new Dialog(this,android.R.style.Theme_Material_NoActionBar);
        LinearLayout layout=stack();layout.setBackgroundColor(0xff101914);
        layout.setPadding(dp(16),dp(6),dp(16),dp(14));
        LinearLayout header=row();header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(action("返回相册",TINT,DARK,v->dialog.dismiss()),new LinearLayout.LayoutParams(-2,-2));
        TextView counter=text("",14,Color.WHITE,true);
        counter.setGravity(Gravity.CENTER);
        header.addView(counter,new LinearLayout.LayoutParams(0,-2,1));
        TextView remove=action("删除照片",0xff49312f,0xffffdddd,null);
        header.addView(remove,new LinearLayout.LayoutParams(-2,-2));
        add(layout,header,0);

        int[] index={selected},sequence={0};
        Runnable[] step={null,null};
        ZoomPhotoView image=new ZoomPhotoView(this,()->step[0].run(),()->step[1].run());
        image.setContentDescription("全屏照片预览，可双击或双指缩放");
        layout.addView(image,new LinearLayout.LayoutParams(-1,0,1));
        TextView status=text("",14,0xffe0ecdf,true);status.setGravity(Gravity.CENTER);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);add(layout,status,4);
        LinearLayout bar=row();
        TextView prev=action("上一张",0xff28473a,Color.WHITE,null),next=action("下一张",0xff28473a,Color.WHITE,null);
        weighted(bar,prev,0);weighted(bar,next,8);
        add(layout,bar,8);
        TextView pick=action("设为当天选片",LIME,DARK,null);
        add(layout,pick,8);

        Future<?>[] job={null};
        Bitmap[] shown={null};
        boolean[] loaded={false};
        Runnable clear=()->{
            image.setImageDrawable(null);
            image.resetViewport();
            shown[0]=null; // A displayed bitmap may still be referenced by the render thread.
        };
        Runnable update=()->{
            File current=newest.get(index[0]);
            String day=TimelineStore.day(current),key=dayKey(day);
            boolean isPick=current.equals(TimelineStore.pick(days.get(day),settings.getString(key,null)));
            counter.setText((index[0]+1)+" / "+newest.size());
            status.setText(day+"   "+TimelineStore.time(current)
                    +(isPick?"   ·   每日选片":"")+(loaded[0]?(shown[0]==null?"   ·   读图失败":""):"   ·   加载中"));
            pick.setText(isPick?"已设为当天选片":"设为当天选片");
            enabled(pick,!isPick&&loaded[0]&&shown[0]!=null);
            enabled(remove,loaded[0]);
            enabled(prev,index[0]>0);enabled(next,index[0]+1<newest.size());
        };
        Runnable refresh=()->{
            if(job[0]!=null)job[0].cancel(true);
            int rev=++sequence[0];
            File current=newest.get(index[0]);
            loaded[0]=false;
            clear.run();update.run();
            job[0]=PREVIEWS.submit(()->{
                Bitmap bitmap=null;
                try{bitmap=PhotoTools.load(current,2000);}catch(RuntimeException|OutOfMemoryError ignored){}
                Bitmap decoded=bitmap;
                runOnUiThread(()->{
                    if(rev!=sequence[0]||!dialog.isShowing()||isDestroyed()){
                        if(decoded!=null)decoded.recycle();
                        return;
                    }
                    shown[0]=decoded;loaded[0]=true;
                    if(decoded!=null)image.setImageBitmap(decoded);
                    image.resetViewport();
                    update.run();
                });
            });
        };
        step[0]=()->{if(index[0]>0){index[0]--;refresh.run();}};
        step[1]=()->{if(index[0]+1<newest.size()){index[0]++;refresh.run();}};
        prev.setOnClickListener(v->step[0].run());
        next.setOnClickListener(v->step[1].run());
        pick.setOnClickListener(v->{
            File current=newest.get(index[0]);
            settings.edit().putString(dayKey(TimelineStore.day(current)),current.getName()).apply();
            adapter.notifyDataSetChanged();
            update.run();
        });
        remove.setOnClickListener(v->{
            File current=newest.get(index[0]);
            confirmDeletePhotos(spaceId,Collections.singletonList(current),dialog);
        });
        dialog.setOnDismissListener(d->{sequence[0]++;if(job[0]!=null)job[0].cancel(true);clear.run();});
        dialog.setContentView(layout);dialog.show();
        Window win=dialog.getWindow();
        if(win!=null){
            win.setLayout(-1,-1);win.setStatusBarColor(0xff101914);win.setNavigationBarColor(0xff101914);
            win.getDecorView().setSystemUiVisibility(0);
        }
        refresh.run();
    }
    private void confirmDeletePhotos(String expectedAlbum,List<File> targets,Dialog preview) {
        if(targets.isEmpty())return;
        if(exporting) {notice("请先完成或取消导出");return;}
        int count=targets.size();
        new AlertDialog.Builder(this).setTitle(count==1?"删除照片？":"删除 "+count+" 张照片？")
            .setMessage("删除后无法恢复，不影响已保存到文件的 GIF 或 MP4。")
            .setNegativeButton("取消",null)
            .setPositiveButton("删除",(d,w)->{
                JSONObject current=store.getSpace(spaceId);
                if(exporting || browsingProjects || current==null || !expectedAlbum.equals(spaceId)) {
                    notice("当前相册已变化，未删除照片");return;
                }
                // Recheck every target against live album data before deleting.
                HashSet<File> live=new HashSet<>(projectPhotos(current));
                int deleted=0;
                SharedPreferences.Editor edit=settings.edit();
                for(File file:targets) {
                    String key=dayKey(TimelineStore.day(file)); // Capture date before deleting.
                    if(!live.contains(file)||!file.delete())continue;
                    deleted++;
                    if(file.getName().equals(settings.getString(key,null)))edit.remove(key);
                }
                if(deleted==0){notice("未删除任何照片");return;}
                edit.apply();
                if(spaceId.equals(resultProject)){resultProject=null;resultFile=null;pendingPreview=false;}
                if(preview!=null)preview.dismiss();
                render();
                notice(deleted==count?"已删除 "+deleted+" 张照片":
                        "已删除 "+deleted+" 张，另有 "+(count-deleted)+" 张未删除");
            }).show();
    }

    private void showExportSheet(List<File> photos,LinkedHashMap<String,List<File>> days) {
        if(exporting||photos.isEmpty())return;
        Dialog sheet=new Dialog(this);
        sheet.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout content=panel(CARD,23);content.setPadding(dp(21),dp(16),dp(21),dp(24));
        LinearLayout header=row();header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text("制作影像",23,INK,true),new LinearLayout.LayoutParams(0,-2,1));
        header.addView(action("关闭",TINT,DARK,v->sheet.dismiss()),new LinearLayout.LayoutParams(-2,dp(48)));
        add(content,header,0);
        TextView count=text("",14,MUTED,false);
        add(content,count,3);

        RadioGroup modes=new RadioGroup(this);
        modes.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton daily=new RadioButton(this),all=new RadioButton(this);
        daily.setText("每天一张");daily.setId(View.generateViewId());daily.setMinHeight(dp(46));
        all.setText("全部照片");all.setId(View.generateViewId());all.setMinHeight(dp(46));
        modes.addView(daily,new RadioGroup.LayoutParams(0,-2,1));
        modes.addView(all,new RadioGroup.LayoutParams(0,-2,1));
        modes.check(allFrames?all.getId():daily.getId());
        add(content,modes,5);

        String alignmentKey="align_people_"+spaceId;
        CheckBox align=new CheckBox(this);align.setText("对齐人物");align.setMinHeight(dp(46));
        align.setChecked(settings.getBoolean(alignmentKey,false));
        align.setOnCheckedChangeListener((button,checked)->settings.edit().putBoolean(alignmentKey,checked).apply());
        add(content,align,0);
        add(content,text("适合角度相近的单人照，只改变导出画面。",12,MUTED,false),0);
        Runnable sync=()->{
            int frames=allFrames?photos.size():days.size();
            count.setText(frames+" 帧 · 按拍摄时间排序");
            enabled(align,frames>1);
        };
        modes.setOnCheckedChangeListener((group,id)->{allFrames=id==all.getId();sync.run();});
        sync.run();
        LinearLayout buttons=row();
        weighted(buttons,action("GIF 动图",TINT,DARK,v->{
            sheet.dismiss();exportTimeline(allFrames?photos:chosenFrames(days),false,align.isEnabled()&&align.isChecked());
        }),0);
        weighted(buttons,action("MP4 视频",DARK,Color.WHITE,v->{
            sheet.dismiss();exportTimeline(allFrames?photos:chosenFrames(days),true,align.isEnabled()&&align.isChecked());
        }),8);
        add(content,buttons,10);
        sheet.setContentView(content);
        Window win=sheet.getWindow();
        if(win!=null){
            win.setBackgroundDrawableResource(android.R.color.transparent);
            win.setGravity(Gravity.BOTTOM);
        }
        sheet.show();
        if(win!=null)win.setLayout(-1,-2);
    }
    private void updateProgress(int percent) {
        exportPercent=Math.max(exportPercent,Math.max(0,Math.min(100,percent)));
        if(progressBar!=null) progressBar.setProgress(exportPercent);
        if(progressLabel!=null) progressLabel.setText("正在生成影像 · "+exportPercent+"%");
    }
    private void cancelTimelineExport() {
        if(!timelineExporting)return;
        exportGeneration++;
        if(exportTask!=null) exportTask.cancel(true);
        exportTask=null; timelineExporting=false; exporting=false; exportPercent=0;
        render();
        notice("已取消导出");
    }
    private void exportTimeline(List<File> frames,boolean video,boolean alignPeople) {
        final int generation=++exportGeneration;
        final String project=spaceId;
        exporting=true; timelineExporting=true; exportPercent=0;
        render();
        exportTask=IO.submit(()->{
            try {
                PersonAlignment alignment=alignPeople?new PersonAlignment(getApplicationContext()):null;
                File file=Timelapse.export(getApplicationContext(),frames,video,pct->
                    runOnUiThread(()->{
                        if(generation==exportGeneration&&timelineExporting&&!isDestroyed()) updateProgress(pct);
                    }),alignment);
                String alignmentSummary=alignment==null?null:alignment.summary();
                runOnUiThread(()->{
                    if(generation!=exportGeneration||isFinishing()||isDestroyed()) {file.delete();return;}
                    exportTask=null; exporting=false; timelineExporting=false;
                    resultFile=file; resultVideo=video; resultProject=project; resultAlignment=alignmentSummary;
                    pendingPreview=true;
                    render();
                    if(isForeground && project.equals(spaceId) && !browsingProjects) presentExport(file,video);
                });
            } catch(Exception | OutOfMemoryError e) {
                runOnUiThread(()->{
                    if(generation!=exportGeneration||isFinishing()||isDestroyed())return;
                    exportTask=null; exporting=false; timelineExporting=false;
                    android.util.Log.w("FrameAgain","Could not export timeline",e);
                    render();
                    if(isForeground) notice(video?"无法生成 MP4，请试试 GIF":"无法生成 GIF，请检查照片和存储空间");
                });
            }
        });
    }
    private void presentExport(File file,boolean video) {
        if(previewInFlight)return;
        pendingPreview=false;
        if(file==null||!file.isFile()) {notice("导出文件已清理，请重新生成");return;}
        try {
            previewInFlight=true;
            startActivityForResult(ExportPreviewActivity.intent(this,file,video,resultAlignment),EXPORT_PREVIEW);
        } catch(RuntimeException e) {
            previewInFlight=false;
            notice("无法打开导出预览");
        }
    }

    private interface OnInput { void use(String s) throws Exception; }
    private void input(String title,String hint,String preset,OnInput handler) {
        LinearLayout wrap=stack();
        wrap.setPadding(dp(24),dp(5),dp(24),0);
        EditText field=new EditText(this);
        field.setSingleLine(true); field.setHint(hint);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(36)});
        field.setText(preset);field.setSelection(field.length());
        wrap.addView(field,new LinearLayout.LayoutParams(-1,-2));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setView(wrap)
            .setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{
            String s=field.getText().toString().trim();
            if(s.isEmpty()) {field.setError("请输入名称");return;}
            try { handler.use(s); dialog.dismiss(); }
            catch(IllegalArgumentException|IllegalStateException e) { field.setError(e.getMessage()); }
            catch(Exception e) { notice("保存失败，请检查可用存储空间"); }
        }));
        dialog.show();
    }
    private void heading(String title,String sub) {
        add(screen,text(title,42,INK,true),5);
        add(screen,text(sub,16,MUTED,false),6);
    }
    private TextView text(String s,int size,int color,boolean bold) {
        TextView t=new TextView(this);t.setText(s);t.setTextColor(color);t.setTextSize(size);
        if(bold)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        t.setLineSpacing(dp(2),1f);return t;
    }
    private TextView action(String s,int bg,int color,View.OnClickListener cb) {
        TextView t=text(s,14,color,true);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(48));t.setPadding(dp(9),dp(5),dp(9),dp(5));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000),rounded(bg,13),null));
        t.setOnClickListener(cb);
        t.setFocusable(true);
        return t;
    }
    private GradientDrawable rounded(int color,int radius) {
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;
    }
    private LinearLayout stack() {LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row() {LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    private LinearLayout panel(int color,int radius) {
        LinearLayout l=stack();l.setBackground(rounded(color,radius));l.setPadding(dp(15),dp(14),dp(15),dp(14));return l;
    }
    private void add(LinearLayout p,View v,int margin) {
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin=dp(margin);p.addView(v,lp);
    }
    private void weighted(LinearLayout l,View v,int margin) {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.leftMargin=dp(margin);l.addView(v,p);
    }
    private int dp(float x) {return Math.round(x*getResources().getDisplayMetrics().density);}
    private void notice(String s) {Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
}
