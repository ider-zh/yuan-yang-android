package com.yuanyang.camera;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Movie;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Private, format-aware preview for completed timeline exports. */
public final class ExportPreviewActivity extends Activity {
    private static final int BG=0xfff2f5ee, DARK=0xff18382d, TINT=0xffe4f0e0, INK=0xff172e25;
    private static final int PICK_DOCUMENT=51, SHARE_MEDIA=52;
    private static final ExecutorService DECODERS=Executors.newSingleThreadExecutor();
    private static final ExecutorService SAVES=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());

    private File file;
    private boolean video, active, loaded, previewResolved, previewFailed, prepared;
    private boolean playOnResume=true, scrubbing;
    private int playbackPosition, externalAction;
    private FrameLayout preview;
    private TextView stateLabel, actionStatus, play, share, save, time;
    private SeekBar bar;
    private VideoView player;
    private MediaPlayer playerMedia;
    private GifPlayback gif;
    private SaveJob saveJob;
    private Future<?> decoding;

    static Intent intent(Context context, File export, boolean video, String alignmentSummary) {
        return new Intent(context,ExportPreviewActivity.class)
            .putExtra("export",export.getName()).putExtra("video",video).putExtra("alignment",alignmentSummary);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(DARK);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        video=getIntent().getBooleanExtra("video",false);
        String name=getIntent().getStringExtra("export");
        if(state!=null) {
            playbackPosition=state.getInt("position");
            playOnResume=state.getBoolean("play",true);
            externalAction=state.getInt("externalAction");
        }
        Object previous=getLastNonConfigurationInstance();
        if(previous instanceof SaveJob) saveJob=(SaveJob)previous;
        file=resolve(name);
        createScreen(getIntent().getStringExtra("alignment"));
        if(saveJob!=null && saveJob.state==0) showStatus("正在保存副本…");
        if(file==null) playbackFailed("导出文件已清理，请返回重新生成。");
        else if(video) initializePlayer();
        else loadGif();
        updateActions();
    }

    private File resolve(String name) {
        String mime=ShareProvider.mimeType(name);
        if(!((video?"video/mp4":"image/gif").equals(mime))) return null;
        try {
            File root=new File(getCacheDir(),ShareProvider.DIRECTORY).getCanonicalFile();
            File path=new File(root,name).getCanonicalFile();
            return root.equals(path.getParentFile()) && path.isFile() && path.length()>0 ? path:null;
        } catch(IOException ignored) { return null; }
    }

    private void createScreen(String summary) {
        LinearLayout column=new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(18),dp(10),dp(18),dp(14));
        column.setBackgroundColor(BG);
        LinearLayout top=new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back=button("‹  返回记录",TINT,DARK,v->finish());
        top.addView(back,new LinearLayout.LayoutParams(-2,dp(48)));
        TextView title=label("导出预览",22,INK);
        title.setTypeface(null,android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        top.addView(title,new LinearLayout.LayoutParams(0,dp(48),1f));
        add(column,top,0);
        TextView format=label(video?"MP4 视频":"GIF 动图  ·  循环播放",14,DARK);
        add(column,format,4);
        if(summary!=null && !summary.isEmpty()) add(column,label(summary,13,DARK),5);

        preview=new FrameLayout(this);
        preview.setBackground(rounded(0xff101814));
        preview.setClipToOutline(true);
        column.addView(preview,new LinearLayout.LayoutParams(-1,0,1f));
        LinearLayout.LayoutParams frame=(LinearLayout.LayoutParams)preview.getLayoutParams();
        frame.topMargin=dp(8);
        stateLabel=label("正在载入预览…",15,0xffeff8ef);
        stateLabel.setGravity(Gravity.CENTER);
        stateLabel.setPadding(dp(12),dp(12),dp(12),dp(12));
        stateLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        preview.addView(stateLabel,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));

        if(video) {
            LinearLayout controls=new LinearLayout(this);
            controls.setGravity(Gravity.CENTER_VERTICAL);
            play=button("播放",TINT,DARK,v->toggleVideo());
            play.setEnabled(false);
            controls.addView(play,new LinearLayout.LayoutParams(-2,dp(48)));
            bar=new SeekBar(this);
            bar.setContentDescription("视频进度");
            bar.setEnabled(false);
            controls.addView(bar,new LinearLayout.LayoutParams(0,dp(48),1));
            time=label("0:00.0 / 0:00.0",12,DARK);
            controls.addView(time,new LinearLayout.LayoutParams(-2,-2));
            add(column,controls,4);
        }
        LinearLayout choices=new LinearLayout(this);
        share=button("分享到其他应用",TINT,DARK,v->share());
        save=button("保存到文件",DARK,0xffffffff,v->save());
        choices.addView(share,new LinearLayout.LayoutParams(0,dp(48),1));
        LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,dp(48),1);
        right.leftMargin=dp(8);
        choices.addView(save,right);
        add(column,choices,8);
        actionStatus=label("",13,DARK);
        actionStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        actionStatus.setVisibility(View.GONE);
        add(column,actionStatus,4);
        setContentView(column);
    }

    private void initializePlayer() {
        player=new VideoView(this);
        player.setContentDescription("MP4 视频预览");
        preview.addView(player,0,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        player.setOnPreparedListener(mp->{
            if(isDestroyed() || !loaded)return;
            prepared=true;
            playerMedia=mp;
            int length=player.getDuration();
            bar.setMax(Math.max(length,1));
            if(playbackPosition>0) seek(playbackPosition);
            if(active && playOnResume) player.start();
            previewReady();
            refreshPlayback();
        });
        player.setOnCompletionListener(mp->{
            playOnResume=false;
            playbackPosition=Math.max(0,player.getDuration());
            refreshPlayback();
        });
        player.setOnErrorListener((mp,what,extra)->{
            if(!loaded)return true;
            prepared=false;
            playerMedia=null;
            playbackFailed("无法在此设备上播放预览，可保存后用其他应用打开。");
            return true;
        });
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar view) {scrubbing=true;}
            @Override public void onProgressChanged(SeekBar view,int progress,boolean fromUser) {
                if(fromUser && prepared) {
                    time.setText(clock(progress)+" / "+clock(player.getDuration()));
                    if(!scrubbing) seek(progress); // D-pad and accessibility controls have no touch events.
                }
            }
            @Override public void onStopTrackingTouch(SeekBar view) {
                if(prepared) seek(view.getProgress());
                scrubbing=false;
            }
        });
    }

    private void loadGif() {
        decoding=DECODERS.submit(()->{
            try {
                GifPlayback result=Build.VERSION.SDK_INT>=28
                    ? ModernGif.load(file)
                    : LegacyGif.load(file);
                main.post(()->{
                    if(isDestroyed() || isFinishing())return;
                    gif=result;
                    View v=gif.view(this);
                    v.setContentDescription("GIF 动图预览");
                    preview.addView(v,0,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
                    if(active) gif.start();
                    previewReady();
                });
            } catch(IOException | RuntimeException | OutOfMemoryError failure) {
                main.post(()->{
                    if(!isDestroyed() && !isFinishing())
                        playbackFailed("无法播放 GIF 预览，可保存后用其他应用打开。");
                });
            }
        });
    }

    private interface GifPlayback {
        View view(Context context);
        void start();
        void stop();
    }

    @TargetApi(28)
    private static final class ModernGif implements GifPlayback {
        final Drawable image;
        private ModernGif(Drawable image) {this.image=image;}
        static ModernGif load(File file) throws IOException {
            return new ModernGif(ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)));
        }
        @Override public View view(Context context) {
            ImageView v=new ImageView(context);
            v.setScaleType(ImageView.ScaleType.FIT_CENTER);
            v.setImageDrawable(image);
            if(image instanceof AnimatedImageDrawable)
                ((AnimatedImageDrawable)image).setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
            return v;
        }
        @Override public void start() {
            if(image instanceof AnimatedImageDrawable) ((AnimatedImageDrawable)image).start();
        }
        @Override public void stop() {
            if(image instanceof AnimatedImageDrawable) ((AnimatedImageDrawable)image).stop();
        }
    }

    @SuppressWarnings("deprecation")
    private static final class LegacyGif implements GifPlayback {
        final Movie movie;
        private MovieView widget;
        private LegacyGif(Movie movie) {this.movie=movie;}
        static LegacyGif load(File file) throws IOException {
            Movie movie=Movie.decodeFile(file.getPath());
            if(movie==null || movie.width()<=0 || movie.height()<=0) throw new IOException("GIF decode failed");
            return new LegacyGif(movie);
        }
        @Override public View view(Context context) {
            widget=new MovieView(context,movie);
            return widget;
        }
        @Override public void start() {if(widget!=null)widget.play(true);}
        @Override public void stop() {if(widget!=null)widget.play(false);}

        private static final class MovieView extends View {
            final Movie movie;
            long started, elapsed;
            boolean running;
            MovieView(Context context,Movie movie) {
                super(context);
                this.movie=movie;
                setLayerType(View.LAYER_TYPE_SOFTWARE,null);
            }
            void play(boolean start) {
                if(start==running)return;
                long now=SystemClock.uptimeMillis();
                if(start) started=now;
                else elapsed+=now-started;
                running=start;
                invalidate();
            }
            @Override public void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                long time=elapsed+(running?SystemClock.uptimeMillis()-started:0);
                int duration=movie.duration();
                movie.setTime(duration>0?(int)(time%duration):0);
                float scale=Math.min(getWidth()/(float)movie.width(),getHeight()/(float)movie.height());
                int restore=canvas.save();
                canvas.translate((getWidth()-movie.width()*scale)/2,(getHeight()-movie.height()*scale)/2);
                canvas.scale(scale,scale);
                movie.draw(canvas,0,0);
                canvas.restoreToCount(restore);
                if(running && duration>0) postInvalidateDelayed(30);
            }
        }
    }

    private void previewReady() {
        previewResolved=true;
        stateLabel.setVisibility(View.GONE);
        updateActions();
    }

    private void playbackFailed(String reason) {
        previewResolved=true;
        previewFailed=true;
        stateLabel.setText(reason);
        stateLabel.setVisibility(View.VISIBLE);
        if(play!=null){play.setEnabled(false);bar.setEnabled(false);}
        updateActions();
    }

    private void toggleVideo() {
        if(!prepared)return;
        if(player.isPlaying()) {
            playbackPosition=player.getCurrentPosition();
            playOnResume=false;
            player.pause();
        } else {
            if(playbackPosition>=player.getDuration()) {
                seek(0);
            }
            playOnResume=true;
            player.start();
        }
        refreshPlayback();
    }

    private void seek(int requested) {
        playbackPosition=Math.min(Math.max(0,requested),Math.max(0,player.getDuration()-1));
        if(Build.VERSION.SDK_INT>=26 && playerMedia!=null) {
            playerMedia.seekTo(playbackPosition,MediaPlayer.SEEK_CLOSEST);
        } else {
            player.seekTo(playbackPosition);
        }
    }

    private void refreshPlayback() {
        if(!prepared || previewFailed)return;
        int duration=player.getDuration();
        boolean playing=player.isPlaying();
        if(playing) playbackPosition=player.getCurrentPosition();
        if(!scrubbing) {
            bar.setProgress(playbackPosition);
            time.setText(clock(playbackPosition)+" / "+clock(duration));
        }
        play.setEnabled(true);
        bar.setEnabled(duration>0);
        play.setText(playing?"暂停":"播放");
    }

    private static String clock(int millis) {
        long tenths=Math.max(0,(millis+50L)/100L);
        return String.format(Locale.ROOT,"%d:%02d.%d",tenths/600,(tenths/10)%60,tenths%10);
    }

    private final Runnable tick=new Runnable() {
        @Override public void run() {
            if(!active)return;
            refreshPlayback();
            if(saveJob!=null && saveJob.state!=0) {
                showStatus(saveJob.state==1?"已保存":"保存失败，可重新选择位置再试。");
                saveJob=null;
                updateActions();
            }
            main.postDelayed(this,200);
        }
    };

    @Override protected void onResume() {
        super.onResume();
        active=true;
        if(video && !previewFailed && player!=null) {
            if(!loaded) {
                loaded=true;
                player.setVideoPath(file.getAbsolutePath());
            } else if(prepared && playOnResume) player.start();
        }
        if(gif!=null)gif.start();
        main.removeCallbacks(tick);
        tick.run();
    }

    @Override protected void onPause() {
        active=false;
        main.removeCallbacks(tick);
        if(gif!=null)gif.stop();
        if(prepared && player.isPlaying()) {
            playbackPosition=player.getCurrentPosition();
            player.pause();
        }
        super.onPause();
    }

    @Override protected void onStop() {
        if(player!=null && loaded) {
            prepared=false;
            loaded=false;
            playerMedia=null;
            player.stopPlayback();
            play.setEnabled(false);
            bar.setEnabled(false);
        }
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if(prepared && player.isPlaying()) playbackPosition=player.getCurrentPosition();
        state.putInt("position",playbackPosition);
        state.putBoolean("play",playOnResume);
        state.putInt("externalAction",externalAction);
    }

    @Override public Object onRetainNonConfigurationInstance() {return saveJob;}

    @Override protected void onDestroy() {
        if(decoding!=null)decoding.cancel(true);
        if(gif!=null)gif.stop();
        main.removeCallbacksAndMessages(null);
        if(player!=null)player.stopPlayback();
        super.onDestroy();
    }

    private void share() {
        if(!canOpenAction())return;
        try {
            externalAction=SHARE_MEDIA;
            updateActions();
            startActivityForResult(PhotoTools.shareChooser(this,file),SHARE_MEDIA);
        } catch(RuntimeException failure) {
            externalAction=0;
            showStatus("无法打开系统分享菜单。");
            updateActions();
        }
    }

    private void save() {
        if(!canOpenAction())return;
        String ext=video?".mp4":".gif";
        Intent picker=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(video?"video/mp4":"image/gif")
            .putExtra(Intent.EXTRA_TITLE,"原样-"+System.currentTimeMillis()+ext);
        try {
            externalAction=PICK_DOCUMENT;
            updateActions();
            startActivityForResult(picker,PICK_DOCUMENT);
        } catch(RuntimeException failure) {
            externalAction=0;
            showStatus("无法打开系统文件选择器。");
            updateActions();
        }
    }

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=PICK_DOCUMENT && request!=SHARE_MEDIA)return;
        externalAction=0;
        if(request==PICK_DOCUMENT && result==RESULT_OK && data!=null) {
            Uri target=data.getData();
            if(target!=null && "content".equals(target.getScheme()) && file!=null) {
                saveJob=new SaveJob();
                SaveJob current=saveJob;
                File from=file;
                Context app=getApplicationContext();
                showStatus("正在保存副本…");
                SAVES.execute(()->current.copy(app,from,target));
            } else showStatus("保存位置不可用。");
        }
        updateActions();
    }

    private boolean canOpenAction() {
        return previewResolved && file!=null && externalAction==0 && saveJob==null;
    }

    private void updateActions() {
        boolean enabled=canOpenAction();
        share.setEnabled(enabled);
        save.setEnabled(enabled);
        share.setAlpha(enabled?1f:.48f);
        save.setAlpha(enabled?1f:.48f);
    }

    private void showStatus(String message) {
        actionStatus.setText(message);
        actionStatus.setVisibility(View.VISIBLE);
    }

    private static final class SaveJob {
        volatile int state;
        void copy(Context app,File from,Uri target) {
            try(FileInputStream in=new FileInputStream(from);
                OutputStream out=app.getContentResolver().openOutputStream(target,"wt")) {
                if(out==null)throw new IOException("Missing output stream");
                byte[] buf=new byte[64*1024];
                int read;
                while((read=in.read(buf))!=-1)out.write(buf,0,read);
                out.flush();
            } catch(IOException | RuntimeException failure) {
                state=-1;
                return;
            }
            state=1;
        }
    }

    private int dp(float value) {return Math.round(value*getResources().getDisplayMetrics().density);}
    private GradientDrawable rounded(int color) {
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(12));
        return shape;
    }
    private TextView label(String text,int size,int color) {
        TextView view=new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }
    private TextView button(String text,int color,int ink,View.OnClickListener click) {
        TextView view=label(text,14,ink);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12),0,dp(12),0);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30306955),rounded(color),null));
        view.setFocusable(true);
        view.setOnClickListener(click);
        return view;
    }
    private void add(LinearLayout layout,View item,int top) {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.topMargin=dp(top);
        layout.addView(item,p);
    }
}
