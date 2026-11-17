package com.yuanyang.camera;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends Activity {
    private static final int BG=0xfff2f5ee, CARD=0xffffffff, INK=0xff172e25, MUTED=0xff476056,
        LINE=0xffe0e9df, DARK=0xff18382d, LIME=0xffd2e9bb, TINT=0xffe4f0e0;
    private static final int TIMELINE_CAPTURE=42, EXPORT_PREVIEW=44;
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static final ExecutorService PREVIEWS=Executors.newSingleThreadExecutor();
    private ProjectStore store;
    private TimelineStore timeline;
    private SharedPreferences settings;
    private boolean allFrames, browsingProjects, isForeground;
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
    private ScrollView scroll;
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
            resultProject=state.getString("resultProject");
            String path=state.getString("resultFile");
            if(path!=null) resultFile=new File(path);
            resultVideo=state.getBoolean("resultVideo");
            resultAlignment=state.getString("resultAlignment");
            cameraInFlight=state.getInt("cameraInFlight");
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
        allFrames=false; browsingProjects=false;
        settings.edit().putString("last_project",spaceId).apply();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("space",spaceId);
        out.putBoolean("allFrames",allFrames);
        out.putBoolean("pendingPreview",pendingPreview);
        out.putBoolean("previewInFlight",previewInFlight);
        out.putBoolean("browsingProjects",browsingProjects);
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
    private void showProjectList() { browsingProjects=true; render(); }
    @Override public void onBackPressed() {
        if(browsingProjects) {
            browsingProjects=false;
            if(!captureTimeline()) render();
        } else if(timelineExporting) cancelTimelineExport();
        else finish();
    }
    @Override protected void onActivityResult(int req,int result,Intent data) {
        super.onActivityResult(req,result,data);
        if(req==cameraInFlight) cameraInFlight=0;
        if(req==EXPORT_PREVIEW) { previewInFlight=false; return; }
        if(req==TIMELINE_CAPTURE && store!=null) {
            browsingProjects=result==RESULT_OK && data!=null
                && data.getBooleanExtra(CaptureActivity.RESULT_OPEN_ALBUM_LIST,false);
            render();
        }
    }
    private void render() {
        screenBase();
        JSONObject space=store.getSpace(spaceId);
        if(browsingProjects || space==null) home();
        else projectTimeline(space);
    }
    private void screenBase() {
        progressLabel=null; progressBar=null;
        scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(BG);
        screen=stack();
        screen.setPadding(dp(20),dp(18),dp(20),dp(28));
        scroll.addView(screen,new ScrollView.LayoutParams(-1,-2));
        setContentView(scroll);
    }
    private void home() {
        if(spaceId!=null) add(screen,action("‹  返回拍摄",TINT,DARK,v->onBackPressed()),0);
        add(screen,text("原样  /  FRAME AGAIN",12,MUTED,true),spaceId==null?0:14);
        heading("所有相册","把想长期记录的事，分开放在不同相册里。");
        add(screen,action("＋ 新建相册",DARK,Color.WHITE,v->createAlbum()),20);
        for(int i=0;i<store.spaces.length();i++) {
            JSONObject s=store.spaces.optJSONObject(i);
            if(s==null) continue;
            String name=albumName(s);
            boolean last=s.optString("id").equals(settings.getString("last_project",null));
            LinearLayout item=panel(CARD,18);
            add(item,text(name,20,INK,true),0);
            add(item,text(last?"上次拍摄    ›":"打开相机    ›",14,MUTED,false),5);
            item.setContentDescription(name+"，打开相机");
            item.setFocusable(true);
            item.setOnClickListener(v->{setCurrentProject(s);if(!captureTimeline())render();});
            add(screen,item,9);
        }
        add(screen,text("照片留在本机，只有你主动分享时才会发送。",12,MUTED,false),24);
    }
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
                .putExtra(CaptureActivity.EXTRA_PROJECT,albumName(project));
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
        LinearLayout top=row();
        weighted(top,action("所有相册",TINT,DARK,v->showProjectList()),0);
        weighted(top,action("继续拍摄",DARK,Color.WHITE,v->captureTimeline()),8);
        add(screen,top,0);
        add(screen,text(albumName(space),30,INK,true),15);
        List<File> photos=projectPhotos(space);
        LinkedHashMap<String,List<File>> days=TimelineStore.days(photos);
        add(screen,text(photos.size()+" 张照片  ·  "+days.size()+" 天",14,MUTED,false),5);
        LinearLayout card=panel(DARK,20);
        add(card,text("制作时序影像",22,Color.WHITE,true),0);
        add(card,text("每天默认选最后一张，点日期可更换。测试时可用全部照片。",14,0xffdcece1,false),5);
        LinearLayout mode=row();
        weighted(mode,action((!allFrames?"✓ ":"")+"每天一张",!allFrames?LIME:0xffe4eee8,DARK,v->{allFrames=false;render();}),0);
        weighted(mode,action((allFrames?"✓ ":"")+"全部记录",allFrames?LIME:0xffe4eee8,DARK,v->{allFrames=true;render();}),8);
        add(card,mode,12);
        List<File> frames=allFrames?photos:chosenFrames(days);
        CheckBox align = new CheckBox(this);
        align.setText("对齐人物");
        align.setTextSize(15);
        align.setTextColor(Color.WHITE);
        align.setButtonTintList(ColorStateList.valueOf(LIME));
        align.setMinHeight(dp(48));
        align.setEnabled(frames.size()>1 && !exporting);
        align.setAlpha(align.isEnabled()?1f:.50f);
        String alignmentKey = "align_people_" + spaceId;
        align.setChecked(frames.size()>1 && settings.getBoolean(alignmentKey,false));
        align.setOnCheckedChangeListener((buttonView, checked)->settings.edit().putBoolean(alignmentKey,checked).apply());
        add(card,align,8);
        if (frames.size()>1) add(card,text("适合角度相近的单人照；仅影响导出的画面。",12,0xffdcece1,false),0);
        TextView button=action(exporting?"正在生成…":"导出 GIF / MP4  ·  "+frames.size()+" 帧",LIME,DARK,
                v->chooseFormat(frames,align.isChecked()));
        button.setEnabled(frames.size()>0&&!exporting);
        button.setAlpha(button.isEnabled()?1f:.52f);
        add(card,button,8);
        if(timelineExporting) {
            progressLabel=text("",13,0xffe8f5e8,true);
            add(card,progressLabel,10);
            progressBar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
            progressBar.setMax(100); progressBar.setContentDescription("影像导出进度");
            progressBar.setProgressTintList(ColorStateList.valueOf(LIME));
            add(card,progressBar,6);
            updateProgress(exportPercent);
            add(card,action("取消导出",TINT,DARK,v->cancelTimelineExport()),8);
        } else if(resultFile!=null && resultFile.isFile() && spaceId.equals(resultProject)) {
            if(resultAlignment!=null) add(card,text(resultAlignment,13,0xffe8f5e8,true),10);
            add(card,action("查看上次导出结果",TINT,DARK,v->presentExport(resultFile,resultVideo)),8);
        }
        add(screen,card,16);
        add(screen,text("照片记录",18,INK,true),20);
        if(days.isEmpty()) add(screen,text("还没有照片。拍一张后会在这里出现。",14,MUTED,false),8);
        ArrayList<String> newest=new ArrayList<>(days.keySet());
        Collections.reverse(newest);
        for(String day:newest) {
            List<File> group=days.get(day);
            File pick=TimelineStore.pick(group,settings.getString(dayKey(day),null));
            LinearLayout item=panel(CARD,16);
            add(item,text(day,17,INK,true),0);
            add(item,text(group.size()+" 张 · 选中 "+TimelineStore.time(pick)+" · 查看 / 删除    ›",13,MUTED,false),4);
            item.setOnClickListener(v->pickForDay(day,group,pick));
            item.setFocusable(true);
            add(screen,item,8);
        }
    }
    private void pickForDay(String day,List<File> group,File selected) {
        LinearLayout box=stack();
        box.setPadding(dp(18),dp(8),dp(18),0);
        ImageView preview=new ImageView(this);
        preview.setBackground(rounded(DARK,12));
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setContentDescription("当日照片预览");
        box.addView(preview,new LinearLayout.LayoutParams(-1,dp(180)));
        TextView indicator=text("",14,MUTED,true);
        add(box,indicator,8);
        LinearLayout buttons=row();
        int[] index={Math.max(0,group.indexOf(selected))}, sequence={0};
        Future<?>[] pending={null};
        String key=dayKey(day);
        TextView prev=action("上一张",TINT,DARK,null),next=action("下一张",TINT,DARK,null);
        weighted(buttons,prev,0);weighted(buttons,next,8);add(box,buttons,8);
        TextView remove=action("删除",TINT,0xff9b2c2c,null);
        weighted(buttons,remove,8);
        ScrollView content=new ScrollView(this);
        content.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(day+" · 照片").setView(content)
            .setNegativeButton("关闭",null)
            .setPositiveButton("设为当天选片",(d,w)->{
                settings.edit().putString(key,group.get(index[0]).getName()).apply();
                int y=scroll.getScrollY();render();
                ScrollView refreshed=scroll; refreshed.post(()->refreshed.scrollTo(0,y));
            }).create();
        Runnable refresh=()->{
            File f=group.get(index[0]);
            if(pending[0]!=null) pending[0].cancel(true);
            int rev=++sequence[0];
            preview.setImageDrawable(null);
            indicator.setText((index[0]+1)+" / "+group.size()+"    "+TimelineStore.time(f)+"  ·  加载中");
            prev.setEnabled(index[0]>0); prev.setAlpha(prev.isEnabled()?1f:.45f);
            next.setEnabled(index[0]+1<group.size()); next.setAlpha(next.isEnabled()?1f:.45f);
            remove.setEnabled(false); remove.setAlpha(.5f);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            pending[0]=PREVIEWS.submit(()->{
                Bitmap image=PhotoTools.load(f,960);
                runOnUiThread(()->{
                    if(rev!=sequence[0]||!dialog.isShowing()||isDestroyed()){
                        if(image!=null)image.recycle(); return;
                    }
                    preview.setImageBitmap(image);
                    indicator.setText((index[0]+1)+" / "+group.size()+"    "+TimelineStore.time(f)+(image==null?"  ·  读图失败":""));
                    remove.setEnabled(true); remove.setAlpha(1f);
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(image!=null);
                });
            });
        };
        prev.setOnClickListener(v->{if(index[0]>0){index[0]--;refresh.run();}});
        next.setOnClickListener(v->{if(index[0]+1<group.size()){index[0]++;refresh.run();}});
        remove.setOnClickListener(v->confirmDelete(group.get(index[0]),key,dialog));
        dialog.setOnDismissListener(d->{sequence[0]++;if(pending[0]!=null)pending[0].cancel(true);});

        dialog.show();refresh.run();
    }
    private void confirmDelete(File photo,String dayKey,AlertDialog preview) {
        if(exporting) {notice("请先完成或取消导出");return;}
        new AlertDialog.Builder(this).setTitle("删除照片？")
            .setMessage("只删除正在看的这一张。删除后无法恢复，不影响已保存到文件的 GIF 或 MP4。")
            .setNegativeButton("取消",null)
            .setPositiveButton("删除",(d,w)->{
                JSONObject current=store.getSpace(spaceId);
                // Recheck membership against live album data; never delete a passed-in path blindly.
                if(exporting || browsingProjects || current==null || !projectPhotos(current).contains(photo)) {
                    notice("这张照片已不在当前相册"); return;
                }
                if(!photo.delete()) {notice("删除失败，照片仍保留");return;}
                if(photo.getName().equals(settings.getString(dayKey,null))) settings.edit().remove(dayKey).apply();
                if(spaceId.equals(resultProject)) {resultProject=null; resultFile=null;}
                int y=scroll.getScrollY();
                preview.dismiss();
                render();
                ScrollView refreshed=scroll;
                refreshed.post(()->refreshed.scrollTo(0,y));
                notice("已删除照片");
            }).show();
    }
    private void chooseFormat(List<File> frames,boolean alignPeople) {
        if(exporting||frames.isEmpty())return;
        new AlertDialog.Builder(this).setTitle("导出 "+frames.size()+" 帧")
            .setItems(new String[]{"GIF 动图","MP4 视频"},(d,w)->exportTimeline(frames,w==1,alignPeople && frames.size()>1))
            .setNegativeButton("取消",null).show();
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
