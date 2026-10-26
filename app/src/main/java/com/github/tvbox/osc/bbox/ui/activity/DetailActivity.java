package com.github.tvbox.osc.bbox.ui.activity;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.text.Html;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.content.ClipboardManager;
import android.content.ClipData;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.FragmentContainerView;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearSmoothScroller;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.bbox.R;
import com.github.tvbox.osc.bbox.api.ApiConfig;
import com.github.tvbox.osc.bbox.base.App;
import com.github.tvbox.osc.bbox.base.BaseActivity;
import com.github.tvbox.osc.bbox.bean.AbsXml;
import com.github.tvbox.osc.bbox.bean.Movie;
import com.github.tvbox.osc.bbox.bean.SourceBean;
import com.github.tvbox.osc.bbox.bean.VodInfo;
import com.github.tvbox.osc.bbox.cache.RoomDataManger;
import com.github.tvbox.osc.bbox.event.RefreshEvent;
import com.github.tvbox.osc.bbox.picasso.RoundTransformation;
import com.github.tvbox.osc.bbox.ui.adapter.SeriesAdapter;
import com.github.tvbox.osc.bbox.ui.adapter.SeriesFlagAdapter;
import com.github.tvbox.osc.bbox.ui.dialog.DescDialog;
import com.github.tvbox.osc.bbox.ui.dialog.QuickSearchDialog;
import com.github.tvbox.osc.bbox.ui.fragment.PlayFragment;
import com.github.tvbox.osc.bbox.util.*;
import com.github.tvbox.osc.bbox.viewmodel.SourceViewModel;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;
import com.squareup.picasso.Picasso;
import me.jessyan.autosize.utils.AutoSizeUtils;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.jessyan.autosize.utils.AutoSizeUtils;

import android.graphics.Paint;

/**
 * @author pj567
 * @date :2020/12/22
 * @description:
 */

public class DetailActivity extends BaseActivity {
    private LinearLayout llLayout;
    private FragmentContainerView llPlayerFragmentContainer;
    private View llPlayerFragmentContainerBlock;
    private View llPlayerPlace;
    private PlayFragment playFragment = null;
    private ImageView ivThumb;
    private TextView tvName;
    private TextView tvYear;
    private TextView tvSite;
    private TextView tvArea;
    private TextView tvLang;
    private TextView tvType;
    private TextView tvActor;
    private TextView tvDirector;
    private TextView tvPlayUrl;
    private TextView tvDes;
    private TextView tvPlay;
    //    private TextView tvSort;
    private TextView tvDesc;
    private TextView tvSeriesSort;
    private TextView tvQuickSearch;
    private TextView tvCollect;
    private TextView tvDownload;
    private TvRecyclerView mGridViewFlag;
    private TvRecyclerView mGridView;
    private TvRecyclerView mSeriesGroupView;
    private LinearLayout mEmptyPlayList;
    private LinearLayout tvSeriesGroup;
    private SourceViewModel sourceViewModel;
    private Movie.Video mVideo;
    private VodInfo vodInfo;
    private SeriesFlagAdapter seriesFlagAdapter;
    private BaseQuickAdapter<String, BaseViewHolder> seriesGroupAdapter;
    private SeriesAdapter seriesAdapter;
    public String vodId;
    public String sourceKey;
    boolean seriesSelect = false;
    public String firstsourceKey;
    private View seriesFlagFocus = null;
    private boolean isReverse;
    private String preFlag="";
    private boolean firstReverse;
    private boolean defaultReverseApplied; // 剧集列表默认倒序，仅每个详情页会话执行一次
    private V7GridLayoutManager mGridViewLayoutMgr = null;
    private HashMap<String, String> mCheckSources = null;
    private final ArrayList<String> seriesGroupOptions = new ArrayList<>();
    private View currentSeriesGroupView;
    private int GroupCount;
    boolean showPreview = Hawk.get(HawkConfig.SHOW_PREVIEW, true);; // true 开启 false 关闭

    private LinearSmoothScroller smoothScroller;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_detail;
    }

    @Override
    protected void init() {
        EventBus.getDefault().register(this);
        initView();
        initViewModel();
        initData();
    }

    private void initView() {
        llLayout = findViewById(R.id.llLayout);
        llPlayerPlace = findViewById(R.id.previewPlayerPlace);
        llPlayerFragmentContainer = findViewById(R.id.previewPlayer);
        llPlayerFragmentContainerBlock = findViewById(R.id.previewPlayerBlock);
        ivThumb = findViewById(R.id.ivThumb);
        llPlayerPlace.setVisibility(showPreview ? View.VISIBLE : View.GONE);
        ivThumb.setVisibility(!showPreview ? View.VISIBLE : View.GONE);
        tvName = findViewById(R.id.tvName);
        tvYear = findViewById(R.id.tvYear);
        tvSite = findViewById(R.id.tvSite);
        tvArea = findViewById(R.id.tvArea);
        tvLang = findViewById(R.id.tvLang);
        tvType = findViewById(R.id.tvType);
        tvActor = findViewById(R.id.tvActor);
        tvDirector = findViewById(R.id.tvDirector);
        tvPlayUrl = findViewById(R.id.tvPlayUrl);
        tvDes = findViewById(R.id.tvDes);
        tvPlay = findViewById(R.id.tvPlay);
//        tvSort = findViewById(R.id.tvSort);
        tvDesc = findViewById(R.id.tvDesc);
        tvSeriesSort = findViewById(R.id.mSeriesSortTv);
        tvCollect = findViewById(R.id.tvCollect);
        tvDownload = findViewById(R.id.tvDownload);
        tvQuickSearch = findViewById(R.id.tvQuickSearch);
        mEmptyPlayList = findViewById(R.id.mEmptyPlaylist);
        mGridView = findViewById(R.id.mGridView);
        mGridView.setHasFixedSize(false);
        this.mGridViewLayoutMgr = new V7GridLayoutManager(this.mContext, 6);
        mGridView.setLayoutManager(this.mGridViewLayoutMgr);
//        mGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 0, false));

        smoothScroller = new LinearSmoothScroller(mContext) {
            @Override
            protected float calculateSpeedPerPixel(DisplayMetrics displayMetrics) {
                return 100f / displayMetrics.densityDpi;
            }
            @Override
            public PointF computeScrollVectorForPosition(int targetPosition) {
                return mGridViewLayoutMgr.computeScrollVectorForPosition(targetPosition);
            }
        };

        seriesAdapter = new SeriesAdapter(this.mGridViewLayoutMgr);
        mGridView.setAdapter(seriesAdapter);
        mGridViewFlag = findViewById(R.id.mGridViewFlag);
        mGridViewFlag.setHasFixedSize(true);
        mGridViewFlag.setLayoutManager(new V7LinearLayoutManager(this.mContext, 0, false));
        seriesFlagAdapter = new SeriesFlagAdapter();
        mGridViewFlag.setAdapter(seriesFlagAdapter);
        isReverse = false;
        firstReverse = false;
        preFlag = "";
        if (showPreview) {
            playFragment = new PlayFragment();
            getSupportFragmentManager().beginTransaction().add(R.id.previewPlayer, playFragment).commit();
            getSupportFragmentManager().beginTransaction().show(playFragment).commitAllowingStateLoss();
            tvPlay.setText("全 屏");
        }
        llPlayerFragmentContainerBlock.setFocusable(showPreview);

        mSeriesGroupView = findViewById(R.id.mSeriesGroupView);
        tvSeriesGroup = findViewById(R.id.mSeriesGroupTv);
        mSeriesGroupView.setHasFixedSize(true);
        mSeriesGroupView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 0, false));
        seriesGroupAdapter = new BaseQuickAdapter<String, BaseViewHolder>(R.layout.item_series_group, seriesGroupOptions) {
            @Override
            protected void convert(BaseViewHolder helper, String item) {
                TextView tvSeries = helper.getView(R.id.tvSeriesGroup);
                tvSeries.setText(item);
                if (helper.getLayoutPosition() == getData().size() - 1) {
                    helper.itemView.setId(ViewCompat.generateViewId());
                    helper.itemView.setNextFocusRightId(helper.itemView.getId());
                }else {
                    helper.itemView.setNextFocusRightId(View.NO_ID);
                }
            }
        };
        mSeriesGroupView.setAdapter(seriesGroupAdapter);


        llPlayerFragmentContainerBlock.setOnClickListener(v -> {
            toggleFullPreview();
            if (firstReverse) {
                jumpToPlay();
                firstReverse=false;
            }
        });
        //禁用播放地址焦点
        tvPlayUrl.setFocusable(false);

        tvPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                if (showPreview) {
                    toggleFullPreview();
                    if(firstReverse){
                        jumpToPlay();
                        firstReverse=false;
                    }
                } else {
                    jumpToPlay();
                }
            }
        });

        tvQuickSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startQuickSearch();
                QuickSearchDialog quickSearchDialog = new QuickSearchDialog(DetailActivity.this);
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH, quickSearchData));
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_WORD, quickSearchWord));
                quickSearchDialog.show();
                if (pauseRunnable != null && pauseRunnable.size() > 0) {
                    searchExecutorService = Executors.newFixedThreadPool(5);
                    for (Runnable runnable : pauseRunnable) {
                        searchExecutorService.execute(runnable);
                    }
                    pauseRunnable.clear();
                    pauseRunnable = null;
                }
                quickSearchDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        try {
                            if (searchExecutorService != null) {
                                pauseRunnable = searchExecutorService.shutdownNow();
                                searchExecutorService = null;
                            }
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                    }
                });
            }
        });
        tvCollect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = tvCollect.getText().toString();
                if ("☆收 藏".equals(text)) {
                    RoomDataManger.insertVodCollect(sourceKey, vodInfo);
                    Toast.makeText(DetailActivity.this, "已加入收藏夹", Toast.LENGTH_SHORT).show();
                    tvCollect.setText("★收 藏");
                } else {
                    RoomDataManger.deleteVodCollect(sourceKey, vodInfo);
                    Toast.makeText(DetailActivity.this, "已移除收藏夹", Toast.LENGTH_SHORT).show();
                    tvCollect.setText("☆收 藏");
                }
            }
        });
        tvDownload.setVisibility(Hawk.get(HawkConfig.SHOW_PREVIEW, true) ? View.VISIBLE : View.GONE);
        tvDownload.setOnClickListener(v -> {
            use1DMDownload();
        });
        tvPlayUrl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //获取剪切板管理器
                ClipboardManager cm = (ClipboardManager)getSystemService(mContext.CLIPBOARD_SERVICE);
                //设置内容到剪切板
                cm.setPrimaryClip(ClipData.newPlainText(null, tvPlayUrl.getText().toString().replace("播放地址：","")));
                Toast.makeText(DetailActivity.this, "已复制", Toast.LENGTH_SHORT).show();
            }
        });


        tvSeriesSort.setOnClickListener(new View.OnClickListener() {
            @SuppressLint("NotifyDataSetChanged")
            @Override
            public void onClick(View v) {
                if (vodInfo != null && vodInfo.seriesMap.size() > 0) {
                    vodInfo.reverseSort = !vodInfo.reverseSort;
                    isReverse = !isReverse;
                    tvSeriesSort.setText(isReverse?"倒序":"正序");
                    vodInfo.reverse();
                    vodInfo.playIndex=(vodInfo.seriesMap.get(vodInfo.playFlag).size()-1)-vodInfo.playIndex;
                    firstReverse = !firstReverse;
                    setSeriesGroupOptions();
                    seriesAdapter.notifyDataSetChanged();

                    customSeriesScrollPos(vodInfo.playIndex);
                    if(currentSeriesGroupView != null) {
                        TextView txtView = currentSeriesGroupView.findViewById(R.id.tvSeriesGroup);
                        txtView.setTextColor(Color.WHITE);
                    }
                }
            }
        });
        tvDesc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        FastClickCheckUtil.check(v);
                        DescDialog dialog = new DescDialog(mContext);
                        dialog.setDescribe(removeHtmlTag(mVideo.des));
                        dialog.show();
                    }
                });
            }
        });

        mGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                seriesSelect = false;
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                seriesSelect = true;
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
            }
        });
        mGridViewFlag.setOnItemListener(new TvRecyclerView.OnItemListener() {
            private void refresh(View itemView, int position) {
                String newFlag = seriesFlagAdapter.getData().get(position).name;
                if (vodInfo != null && !vodInfo.playFlag.equals(newFlag)) {
                    for (int i = 0; i < vodInfo.seriesFlags.size(); i++) {
                        VodInfo.VodSeriesFlag flag = vodInfo.seriesFlags.get(i);
                        if (flag.name.equals(vodInfo.playFlag)) {
                            flag.selected = false;
                            seriesFlagAdapter.notifyItemChanged(i);
                            break;
                        }
                    }
                    if (position < 0 || position >= vodInfo.seriesFlags.size()) return;
                    VodInfo.VodSeriesFlag flag = vodInfo.seriesFlags.get(position);
                    flag.selected = true;
                    // NOTE(2026-10-25): 线路切换时 seriesMap/playFlag/playIndex 可能不一致
                    //（快速切换线路 / 数据未就绪）→ 加两级空值与越界保护
                    java.util.List<VodInfo.VodSeries> curSeries = vodInfo.seriesMap.get(vodInfo.playFlag);
                    if (curSeries != null && vodInfo.playIndex >= 0
                            && vodInfo.playIndex < curSeries.size()) {
                        curSeries.get(vodInfo.playIndex).selected = false;
                    }
                    vodInfo.playFlag = newFlag;
                    seriesFlagAdapter.notifyItemChanged(position);
                    refreshList();
                    mGridView.clearFocus();
                }
                seriesFlagFocus = itemView;
            }

            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
//                seriesSelect = false;
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                refresh(itemView, position);
//                if(isReverse)vodInfo.reverse();
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                refresh(itemView, position);
//                if(isReverse)vodInfo.reverse();
            }
        });
        seriesAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                if (vodInfo != null && vodInfo.seriesMap.get(vodInfo.playFlag).size() > 0) {
                    boolean reload = false;
                    boolean isAllowFull = false;
                    for (int j = 0; j < vodInfo.seriesMap.get(vodInfo.playFlag).size(); j++) {
                        seriesAdapter.getData().get(j).selected = false;
                        seriesAdapter.notifyItemChanged(j);
                    }
                    //解决倒叙不刷新
                    if (vodInfo.playIndex != position) {
                        seriesAdapter.getData().get(position).selected = true;
                        seriesAdapter.notifyItemChanged(position);
                        vodInfo.playIndex = position;

                        reload = true;
                    }
                    //解决当前集不刷新的BUG
                    if (!preFlag.isEmpty() && !vodInfo.playFlag.equals(preFlag)) {
                        reload = true;
                        isAllowFull = true;
                    }

                    seriesAdapter.getData().get(vodInfo.playIndex).selected = true;
                    seriesAdapter.notifyItemChanged(vodInfo.playIndex);
                    //选集全屏 想选集不全屏的注释下面一行
                    if (showPreview && !fullWindows && !isAllowFull && playFragment.getPlayer().isPlaying())toggleFullPreview();
                    if (!showPreview || reload) {
                        jumpToPlay();
                        firstReverse=false;
                    }
                }
            }
        });

        mSeriesGroupView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                TextView txtView = itemView.findViewById(R.id.tvSeriesGroup);
                txtView.setTextColor(Color.WHITE);
//                currentSeriesGroupView = null;
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                TextView txtView = itemView.findViewById(R.id.tvSeriesGroup);
                txtView.setTextColor(mContext.getResources().getColor(R.color.color_02F8E1));
                if (vodInfo != null && vodInfo.seriesMap.get(vodInfo.playFlag).size() > 0) {
                    int targetPos = position * GroupCount;
//                    mGridView.smoothScrollToPosition(targetPos);
                    customSeriesScrollPos(targetPos);
                }
                currentSeriesGroupView = itemView;
                currentSeriesGroupView.isSelected();
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) { }
        });
        tvSeriesSort.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                tvSeriesSort.setTextColor(mContext.getResources().getColor(R.color.color_02F8E1));
                if (vodInfo != null && Objects.requireNonNull(vodInfo.seriesMap.get(vodInfo.playFlag)).size() > 0) {
                    int firstVisible = mGridView.getFirstVisiblePosition();
                    int lastVisible = mGridView.getLastVisiblePosition();
                    if (vodInfo.playIndex < firstVisible || vodInfo.playIndex > lastVisible) {
                        customSeriesScrollPos(vodInfo.playIndex);
                    }
                }
            } else {
                tvSeriesSort.setTextColor(Color.WHITE);
            }
        });
        seriesGroupAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                TextView newTxtView = view.findViewById(R.id.tvSeriesGroup);
                newTxtView.setTextColor(mContext.getResources().getColor(R.color.color_02F8E1));
                if (vodInfo != null && vodInfo.seriesMap.get(vodInfo.playFlag).size() > 0) {
                    int targetPos =  position * GroupCount+1;

                    customSeriesScrollPos(targetPos);
                }
                if(currentSeriesGroupView != null) {
                    TextView txtView = currentSeriesGroupView.findViewById(R.id.tvSeriesGroup);
                    txtView.setTextColor(Color.WHITE);
                }
                currentSeriesGroupView = view;
                currentSeriesGroupView.isSelected();
            }
        });

        if(showPreview){
            llPlayerFragmentContainerBlock.requestFocus();
        }else {
            tvPlay.requestFocus();
        }
        setLoadSir(llLayout);
    }

    //解决类似海贼王的超长动漫 焦点滚动失败的问题
    void customSeriesScrollPos(int targetPos)
    {
        mGridViewLayoutMgr.scrollToPositionWithOffset(targetPos>10?targetPos - 10:0, 0);
        mGridView.postDelayed(() -> {
            this.smoothScroller.setTargetPosition(targetPos);
            mGridViewLayoutMgr.startSmoothScroll(smoothScroller);
            mGridView.smoothScrollToPosition(targetPos);
        }, 50);
    }

    private void initCheckedSourcesForSearch() {
        mCheckSources = SearchHelper.getSourcesForSearch();
    }

    private List<Runnable> pauseRunnable = null;

    private void jumpToPlay() {
        if (vodInfo != null && !vodInfo.seriesMap.get(vodInfo.playFlag).isEmpty()) {
            preFlag = vodInfo.playFlag;
            //更新播放地址
            VodInfo.VodSeries cur = vodInfo.getCurrentSeries();
            if (cur != null) setTextShow(tvPlayUrl, "播放地址：", cur.url);
            Bundle bundle = new Bundle();
            //保存历史
            insertVod(firstsourceKey, vodInfo);
            // insertVod(sourceKey, vodInfo);
            bundle.putString("sourceKey", sourceKey);
//            bundle.putSerializable("VodInfo", vodInfo);
            App.getInstance().setVodInfo(vodInfo);
            if (showPreview) {
                if (previewVodInfo == null) {
                    try {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        ObjectOutputStream oos = new ObjectOutputStream(bos);
                        oos.writeObject(vodInfo);
                        oos.flush();
                        oos.close();
                        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()));
                        previewVodInfo = (VodInfo) ois.readObject();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                if (previewVodInfo != null) {
                    previewVodInfo.playerCfg = vodInfo.playerCfg;
                    previewVodInfo.playFlag = vodInfo.playFlag;
                    previewVodInfo.playIndex = vodInfo.playIndex;
                    previewVodInfo.seriesMap = vodInfo.seriesMap;
//                    bundle.putSerializable("VodInfo", previewVodInfo);
                    App.getInstance().setVodInfo(previewVodInfo);
                }
                playFragment.setData(bundle);
            } else {
                jumpActivity(PlayActivity.class, bundle);
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    void refreshList() {
        if (vodInfo.seriesMap.get(vodInfo.playFlag).size() <= vodInfo.playIndex) {
            vodInfo.playIndex = 0;
        }
        // NOTE(2026-10-25): 默认倒序——最新集在首屏；playIndex 镜像保证「继续播放」仍指向上次那集
        if (!defaultReverseApplied && vodInfo.seriesMap.get(vodInfo.playFlag) != null
                && vodInfo.seriesMap.get(vodInfo.playFlag).size() > 1) {
            defaultReverseApplied = true;
            vodInfo.reverseSort = true;
            isReverse = true;
            tvSeriesSort.setText("倒序");
            vodInfo.reverse();
            vodInfo.playIndex = (vodInfo.seriesMap.get(vodInfo.playFlag).size() - 1) - vodInfo.playIndex;
        }

        if (vodInfo.seriesMap.get(vodInfo.playFlag) != null) {
            boolean canSelect = true;
            for (int j = 0; j < vodInfo.seriesMap.get(vodInfo.playFlag).size(); j++) {
                if(vodInfo.seriesMap.get(vodInfo.playFlag).get(j).selected){
                    canSelect = false;
                    break;
                }
            }
            VodInfo.VodSeries curSel = vodInfo.getCurrentSeries();
            if (canSelect && curSel != null) curSel.selected = true;
        }

        Paint pFont = new Paint();
//        pFont.setTypeface(Typeface.DEFAULT );
        Rect rect = new Rect();

        List<VodInfo.VodSeries> list = vodInfo.seriesMap.get(vodInfo.playFlag);
        int listSize = list.size();
        int w = 1;
        for(int i =0; i < listSize; ++i){
            String name = list.get(i).name;
            pFont.getTextBounds(name, 0, name.length(), rect);
            if(w < rect.width()){
                w = rect.width();
            }
        }
        w += 32;
        int screenWidth = getWindowManager().getDefaultDisplay().getWidth()/3;
        // 左右布局计算偏移
        int playViewWidth = llPlayerPlace.getWidth();
        int offset = (screenWidth-playViewWidth)/w;
        // 上下布局计算偏移
        offset = screenWidth/w;
        if(offset <=2) offset =2;
        if(offset > 6) offset =6;
        mGridViewLayoutMgr.setSpanCount(offset);
        seriesAdapter.setNewData(vodInfo.seriesMap.get(vodInfo.playFlag));

        setSeriesGroupOptions();

        mGridView.postDelayed(new Runnable() {
            @Override
            public void run() {
//                mGridView.smoothScrollToPosition(vodInfo.playIndex);
                customSeriesScrollPos(vodInfo.playIndex);
            }
        }, 100);
    }

    @SuppressLint("NotifyDataSetChanged")
    private void setSeriesGroupOptions(){
        List<VodInfo.VodSeries> list = vodInfo.seriesMap.get(vodInfo.playFlag);
        int listSize = list.size();
        int offset = mGridViewLayoutMgr.getSpanCount();
        seriesGroupOptions.clear();
        GroupCount=(offset==3 || offset==6)?30:20;
        if(listSize>100 && listSize<=400)GroupCount=60;
        if(listSize>400)GroupCount=120;
        if(listSize > 1) {
            tvSeriesGroup.setVisibility(View.VISIBLE);
            int remainedOptionSize = listSize % GroupCount;
            int optionSize = listSize / GroupCount;

            for(int i = 0; i < optionSize; i++) {
                if(vodInfo.reverseSort)
//                    seriesGroupOptions.add(String.format("%d - %d", i * GroupCount + GroupCount, i * GroupCount + 1));
                    seriesGroupOptions.add(String.format("%d - %d", listSize - (i * GroupCount + 1)+1, listSize - (i * GroupCount + GroupCount)+1));
                else
                    seriesGroupOptions.add(String.format("%d - %d", i * GroupCount + 1, i * GroupCount + GroupCount));
            }
            if(remainedOptionSize > 0) {
                if(vodInfo.reverseSort)
//                    seriesGroupOptions.add(String.format("%d - %d", optionSize * GroupCount + remainedOptionSize, optionSize * GroupCount + 1));
                    seriesGroupOptions.add(String.format("%d - %d", listSize - (optionSize * GroupCount + 1)+1, listSize - (optionSize * GroupCount + remainedOptionSize)+1));
                else
                    seriesGroupOptions.add(String.format("%d - %d", optionSize * GroupCount + 1, optionSize * GroupCount + remainedOptionSize));
            }
//            if(vodInfo.reverseSort) Collections.reverse(seriesGroupOptions);

            seriesGroupAdapter.notifyDataSetChanged();
        }else {
            tvSeriesGroup.setVisibility(View.GONE);
        }
    }

    private void setTextShow(TextView view, String tag, String info) {
        if (info == null || info.trim().isEmpty()) {
            view.setVisibility(View.GONE);
            return;
        }
        view.setVisibility(View.VISIBLE);
        view.setText(Html.fromHtml(getHtml(tag, info)));
    }

    private String removeHtmlTag(String info) {
        if (info == null)
            return "";
        return info.replaceAll("\\<.*?\\>", "").replaceAll("\\s", "");
    }

    private void initViewModel() {
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.detailResult.observe(this, new Observer<AbsXml>() {
            @Override
            public void onChanged(AbsXml absXml) {
                if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                    showSuccess();
                    if(!TextUtils.isEmpty(absXml.msg) && !absXml.msg.equals("数据列表")){
                        Toast.makeText(DetailActivity.this, absXml.msg, Toast.LENGTH_SHORT).show();
                        showEmpty();
                        return;
                    }
                    mVideo = absXml.movie.videoList.get(0);

                    mVideo.id = vodId;
                    if (TextUtils.isEmpty(mVideo.name))mVideo.name = "TVBox";
                    vodInfo = new VodInfo();
                    if((mVideo.pic==null || mVideo.pic.isEmpty()) && !vod_picture.isEmpty()){
                        mVideo.pic=vod_picture;
                    }
                    vodInfo.setVideo(mVideo);
                    vodInfo.sourceKey = mVideo.sourceKey;
                    sourceKey = mVideo.sourceKey;

                    tvName.setText(mVideo.name);
                    setTextShow(tvSite, "来源：", ApiConfig.get().getSource(firstsourceKey).getName());
                    setTextShow(tvYear, "年份：", mVideo.year == 0 ? "" : String.valueOf(mVideo.year));
                    setTextShow(tvArea, "地区：", mVideo.area);
                    setTextShow(tvLang, "语言：", mVideo.lang);
                    if (!firstsourceKey.equals(sourceKey)) {
                        setTextShow(tvType, "类型：", "[" + ApiConfig.get().getSource(sourceKey).getName() + "] 解析");
                    } else {
                        setTextShow(tvType, "类型：", mVideo.type);
                    }
                    setTextShow(tvActor, "演员：", mVideo.actor);
                    setTextShow(tvDirector, "导演：", mVideo.director);
                    setTextShow(tvDes, "内容简介：", removeHtmlTag(mVideo.des));
                    if (!TextUtils.isEmpty(mVideo.pic)) {
                        Picasso.get()
                                .load(DefaultConfig.checkReplaceProxy(mVideo.pic))
                                .transform(new RoundTransformation(MD5.string2MD5(mVideo.pic))
                                        .centerCorp(true)
                                        .override(AutoSizeUtils.mm2px(mContext, 300), AutoSizeUtils.mm2px(mContext, 400))
                                        .roundRadius(AutoSizeUtils.mm2px(mContext, 10), RoundTransformation.RoundType.ALL))
                                .placeholder(R.drawable.img_loading_placeholder)
                                .noFade()
                                .error(R.drawable.img_loading_placeholder)
                                .into(ivThumb);
                    } else {
                        ivThumb.setImageResource(R.drawable.img_loading_placeholder);
                    }

                    if (vodInfo.seriesMap != null && vodInfo.seriesMap.size() > 0) {
                        mGridViewFlag.setVisibility(View.VISIBLE);
                        mGridView.setVisibility(View.VISIBLE);
                        tvPlay.setVisibility(View.VISIBLE);
                        mEmptyPlayList.setVisibility(View.GONE);

                        VodInfo vodInfoRecord = RoomDataManger.getVodInfo(sourceKey, vodId);
                        // 读取历史记录
                        if (vodInfoRecord != null) {
                            vodInfo.playIndex = Math.max(vodInfoRecord.playIndex, 0);
                            vodInfo.playFlag = vodInfoRecord.playFlag;
                            vodInfo.playerCfg = vodInfoRecord.playerCfg;
                            vodInfo.reverseSort = vodInfoRecord.reverseSort;
                        } else {
                            vodInfo.playIndex = 0;
                            vodInfo.playFlag = null;
                            vodInfo.playerCfg = "";
                            vodInfo.reverseSort = false;
                        }

                        if (vodInfo.reverseSort) {
                            vodInfo.reverse();
                        }

                        if (vodInfo.playFlag == null || !vodInfo.seriesMap.containsKey(vodInfo.playFlag))
                            vodInfo.playFlag = (String) vodInfo.seriesMap.keySet().toArray()[0];

                        int flagScrollTo = 0;
                        for (int j = 0; j < vodInfo.seriesFlags.size(); j++) {
                            VodInfo.VodSeriesFlag flag = vodInfo.seriesFlags.get(j);
                            if (flag.name.equals(vodInfo.playFlag)) {
                                flagScrollTo = j;
                                flag.selected = true;
                            } else
                                flag.selected = false;
                        }
                        //设置播放地址
                        VodInfo.VodSeries first = vodInfo.getSeriesAt(0);
                        if (first != null) setTextShow(tvPlayUrl, "播放地址：", first.url);
                        seriesFlagAdapter.setNewData(vodInfo.seriesFlags);
                        mGridViewFlag.scrollToPosition(flagScrollTo);

                        // NOTE(2026-10-26 API15 port): 跨源换源后对齐原来看的那一集。
                        // 新源的集数/命名通常与旧源不同（标题可能带全角空格等），
                        // 先按集名精确/包含匹配，匹配不上再按原序号夹取，最后才回第 0 集。
                        applyPendingSeries();

                        refreshList();
                        // NOTE(2026-10-26 API15 port): 选中态必须在 refreshList()（它会
                        // seriesAdapter.setNewData）之后同步，否则跑在空数据上无效。
                        syncSeriesSelected();
                        if (showPreview) {
                            jumpToPlay();
                            llPlayerFragmentContainer.setVisibility(View.VISIBLE);
                            llPlayerFragmentContainerBlock.setVisibility(View.VISIBLE);
                            toggleSubtitleTextSize();
                        }
                        // startQuickSearch();
                    } else {
                        mGridViewFlag.setVisibility(View.GONE);
                        mGridView.setVisibility(View.GONE);
                        tvSeriesGroup.setVisibility(View.GONE);
                        tvPlay.setVisibility(View.GONE);
                        mEmptyPlayList.setVisibility(View.VISIBLE);
                    }
                } else {
                    showEmpty();
                    llPlayerFragmentContainer.setVisibility(View.GONE);
                    llPlayerFragmentContainerBlock.setVisibility(View.GONE);
                }
            }
        });
    }

    /**
     * 跨源换源后把播放集对齐到原来那一集。
     * 优先级：集名完全相同 → 集名互相包含 → 原序号（夹到合法范围） → 第 0 集。
     * 对齐后清空 pending，避免影响后续正常换集。
     */
    private void applyPendingSeries() {
        if (pendingPlayIndex < 0 || vodInfo == null) return;
        try {
            List<VodInfo.VodSeries> list = vodInfo.seriesMap.get(vodInfo.playFlag);
            if (list == null || list.isEmpty()) return;
            int target = -1;
            String want = pendingSeriesName == null ? "" : pendingSeriesName.replace(" ", "").replace("　", "").trim();
            if (!want.isEmpty()) {
                for (int i = 0; i < list.size(); i++) {
                    String n = list.get(i).name;
                    if (n != null && n.replace(" ", "").replace("　", "").trim().equals(want)) {
                        target = i;
                        break;
                    }
                }
                if (target < 0) {
                    for (int i = 0; i < list.size(); i++) {
                        String n = list.get(i).name;
                        if (n == null) continue;
                        String nn = n.replace(" ", "").replace("　", "").trim();
                        if (nn.contains(want) || want.contains(nn)) {
                            target = i;
                            break;
                        }
                    }
                }
            }
            if (target < 0) {
                target = Math.min(pendingPlayIndex, list.size() - 1);
            }
            if (target >= 0) {
                vodInfo.playIndex = target;
                LOG.i("applyPendingSeries: 对齐到第 " + (target + 1) + " 集 (原 index=" + pendingPlayIndex
                        + ", 旧集名=" + pendingSeriesName + ", 新集名=" + list.get(target).name + ")");
            }
        } catch (Throwable th) {
            LOG.e("applyPendingSeries: " + th);
        } finally {
            pendingPlayIndex = -1;
            pendingSeriesName = null;
        }
    }

    /**
     * 换源前记下当前播放位置，供播放页在新源里按时点续播。
     * 取不到（未在播放 / 播放器未就绪）就清空，避免残留旧值影响后续正常播放。
     */
    private void rememberResumeBeforeSourceSwitch() {
        try {
            if (vodInfo == null || vodInfo.id == null) {
                App.getInstance().clearPendingResume();
                return;
            }
            long posMs = 0;
            if (showPreview && playFragment != null && playFragment.getPlayer() != null) {
                posMs = playFragment.getPlayer().getCurrentPosition();
            }
            if (posMs > 3000) {
                App.getInstance().setPendingResume(vodInfo.id, vodInfo.playIndex, posMs);
                LOG.i("rememberResume: pos=" + posMs + " index=" + vodInfo.playIndex + " id=" + vodInfo.id);
            } else {
                App.getInstance().clearPendingResume();
            }
        } catch (Throwable th) {
            LOG.e("rememberResume: " + th);
            App.getInstance().clearPendingResume();
        }
    }

    /** 把剧集网格的高亮同步到 vodInfo.playIndex（换源对齐后必须调，否则高亮停在旧位置） */
    private void syncSeriesSelected() {
        try {
            if (vodInfo == null || seriesAdapter == null) return;
            List<VodInfo.VodSeries> all = seriesAdapter.getData();
            if (all == null || all.isEmpty()) return;
            for (int i = 0; i < all.size(); i++) {
                all.get(i).selected = (i == vodInfo.playIndex);
            }
            seriesAdapter.notifyDataSetChanged();
            final int idx = vodInfo.playIndex;
            mGridView.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        mGridView.setSelection(idx);
                    } catch (Throwable ignore) {
                    }
                }
            });
        } catch (Throwable th) {
            LOG.e("syncSeriesSelected: " + th);
        }
    }

    private String getHtml(String label, String content) {
        if (content == null) {
            content = "";
        }
        return label + "<font color=\"#FFFFFF\">" + content + "</font>";
    }

    private String  vod_picture="";
    private void initData() {
        Intent intent = getIntent();
        if (intent != null && intent.getExtras() != null) {
            Bundle bundle = intent.getExtras();
            vod_picture=bundle.getString("picture", "");
            loadDetail(bundle.getString("id", null), bundle.getString("sourceKey", ""));
        }
    }

    private void loadDetail(String vid, String key) {
        if (vid != null) {
            vodId = vid;
            sourceKey = key;
            firstsourceKey = key;
            showLoading();
            sourceViewModel.getDetail(sourceKey, vodId);
            boolean isVodCollect = RoomDataManger.isVodCollect(sourceKey, vodId);
            if (isVodCollect) {
                tvCollect.setText("★收 藏");
            } else {
                tvCollect.setText("☆收 藏");
            }
        }
    }


    private boolean isFirstLoad = true;
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_REFRESH) {
            if (event.obj != null) {
                if (event.obj instanceof Integer) {
                    int index = (int) event.obj;
                    for (int j = 0; j < Objects.requireNonNull(vodInfo.seriesMap.get(vodInfo.playFlag)).size(); j++) {
                        seriesAdapter.getData().get(j).selected = false;
                        seriesAdapter.notifyItemChanged(j);
                    }
                    seriesAdapter.getData().get(index).selected = true;
                    seriesAdapter.notifyItemChanged(index);
                    if(!isFirstLoad)mGridView.setSelection(index);
                    vodInfo.playIndex = index;
                    //保存历史
                    insertVod(firstsourceKey, vodInfo);
                    isFirstLoad = false;
                } else if (event.obj instanceof JSONObject) {
                    vodInfo.playerCfg = event.obj.toString();
                    //保存历史
                    insertVod(firstsourceKey, vodInfo);
                } else if (event.obj instanceof String) {
                    String url = event.obj.toString();
                    //设置更新播放地址
                    setTvPlayUrl(url);
                    //        insertVod(sourceKey, vodInfo);
                }

            }
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_SELECT) {
            if (event.obj != null) {
                Movie.Video video = (Movie.Video) event.obj;
                // NOTE(2026-10-26 API15 port): 换源前记下当前播放位置，换源后按时点续播。
                // 原来只对齐集数、从 0 重播；progressKey 含 sourceKey，换源后必然取不到旧进度。
                rememberResumeBeforeSourceSwitch();
                loadDetail(video.id, video.sourceKey);
            }
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_WORD_CHANGE) {
            if (event.obj != null) {
                String word = (String) event.obj;
                switchSearchWord(word);
            }
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_RESULT) {
            try {
                searchData(event.obj == null ? null : (AbsXml) event.obj);
            } catch (Exception e) {
                searchData(null);
            }
        } else if (event.type == RefreshEvent.TYPE_PLAY_FAILED) {
            // NOTE(2026-10-26 API15 port): 播放页本源彻底失败（重试+切播放器都不行）后
            // 自动打开「快速搜索」，把同名影片在其它源的结果列出来让用户直接切。
            // 点播原本没有跨源自动换源，而实测最高频的白屏原因就是「源本身不可播」。
            onPlayFailed();
        }
    }

    /** 播放失败 → 自动多源搜索换源（带节流，避免同一轮失败反复弹） */
    private long lastPlayFailedAt = 0;
    /** 跨源换源时用于对齐原来看的那一集 */
    private String pendingSeriesName = null;
    private int pendingPlayIndex = -1;

    private void onPlayFailed() {
        // 只处理详情页内预览播放（含全屏预览）的场景。独立 PlayActivity 时不在这里弹框，
        // 否则会出现「当前 Activity 不在前台却弹 Dialog」。
        if (!showPreview) return;
        if (mVideo == null || mVideo.name == null || mVideo.name.isEmpty()) return;
        if (isFinishing()) return;
        long now = System.currentTimeMillis();
        if (now - lastPlayFailedAt < 20000) return;  // 20 秒内只自动开一次
        lastPlayFailedAt = now;
        // 记住当前集，换源成功后尽量对齐（同源集数/命名不一致时回退同序号）
        try {
            if (vodInfo != null) {
                pendingPlayIndex = vodInfo.playIndex;
                VodInfo.VodSeries cur = vodInfo.getCurrentSeries();
                pendingSeriesName = (cur == null || cur.name == null) ? null : cur.name;
            }
        } catch (Throwable th) {
            pendingSeriesName = null;
            pendingPlayIndex = -1;
        }
        try {
            Toast.makeText(DetailActivity.this, "换个源试试…", Toast.LENGTH_SHORT).show();
            // NOTE(2026-10-26 API15 port): 不能直接 tvQuickSearch.performClick()。
            // 那条路会进 startQuickSearch()，而它有 `if (hadQuickStart) return;` 守卫
            // ⇒ 只有第一次会真正搜索，之后播放失败就什么都不发生（自动换源只生效一次）。
            // 这里显式重置 hadQuickStart 后重跑，保证每次失败都能拿到最新结果。
            hadQuickStart = false;
            tvQuickSearch.performClick();
        } catch (Throwable th) {
            LOG.e("onPlayFailed: " + th);
        }
    }

    private String searchTitle = "";
    private boolean hadQuickStart = false;
    private final List<Movie.Video> quickSearchData = new ArrayList<>();
    private final List<String> quickSearchWord = new ArrayList<>();
    private ExecutorService searchExecutorService = null;

    private void switchSearchWord(String word) {
        OkGo.getInstance().cancelTag("quick_search");
        quickSearchData.clear();
        searchTitle = word;
        searchResult();
    }

    private void startQuickSearch() {
        initCheckedSourcesForSearch();
        if (hadQuickStart)
            return;
        hadQuickStart = true;
        OkGo.getInstance().cancelTag("quick_search");
        quickSearchWord.clear();
        searchTitle = mVideo.name;
        quickSearchData.clear();
        quickSearchWord.addAll(SearchHelper.splitWords(searchTitle));
        // 分词
//        OkGo.<String>get("http://api.pullword.com/get.php?source=" + URLEncoder.encode(searchTitle) + "&param1=0&param2=0&json=1")
//                .tag("fenci")
//                .execute(new AbsCallback<String>() {
//                    @Override
//                    public String convertResponse(okhttp3.Response response) throws Throwable {
//                        if (response.body() != null) {
//                            return response.body().string();
//                        } else {
//                            throw new IllegalStateException("网络请求错误");
//                        }
//                    }
//
//                    @Override
//                    public void onSuccess(Response<String> response) {
//                        String json = response.body();
//                        try {
//                            for (JsonElement je : new Gson().fromJson(json, JsonArray.class)) {
//                                quickSearchWord.add(je.getAsJsonObject().get("t").getAsString());
//                            }
//                        } catch (Throwable th) {
//                            th.printStackTrace();
//                        }
//                        List<String> words = new ArrayList<>(new HashSet<>(quickSearchWord));
//                        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_WORD, words));
//                    }
//
//                    @Override
//                    public void onError(Response<String> response) {super.onError(response);}
//                });

        searchResult();
    }

    private void searchResult() {
        try {
            if (searchExecutorService != null) {
                searchExecutorService.shutdownNow();
                searchExecutorService = null;
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        searchExecutorService = Executors.newFixedThreadPool(5);
        List<SourceBean> searchRequestList = new ArrayList<>();
        searchRequestList.addAll(ApiConfig.get().getSourceBeanList());
        SourceBean home = ApiConfig.get().getHomeSourceBean();
        searchRequestList.remove(home);
        searchRequestList.add(0, home);

        ArrayList<String> siteKey = new ArrayList<>();
        for (SourceBean bean : searchRequestList) {
            if (!bean.isSearchable() || !bean.isQuickSearch()) {
                continue;
            }
            if (mCheckSources != null && !mCheckSources.containsKey(bean.getKey())) {
                continue;
            }
            siteKey.add(bean.getKey());
        }
        for (String key : siteKey) {
            searchExecutorService.execute(new Runnable() {
                @Override
                public void run() {
                    sourceViewModel.getQuickSearch(key, searchTitle);
                }
            });
        }
    }

    private void searchData(AbsXml absXml) {
        if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
            List<Movie.Video> data = new ArrayList<>();
            for (Movie.Video video : absXml.movie.videoList) {
                // 去除当前相同的影片
                if (video.sourceKey.equals(sourceKey) && video.id.equals(vodId))
                    continue;
                data.add(video);
            }
            // NOTE(2026-10-26 API15 port): ★ 去重后再追加。
            // searchData() 是**每个源各回调一次**（searchResult() 按源并发下发请求），
            // 而 quickSearchData 只在 startQuickSearch() 里 clear 一次
            // ⇒ 原来直接 addAll 会让结果列表出现重复条目（同一个源的同一部片出现多次）。
            // 接上自动换源后搜的源更多，重复会更明显。
            for (Movie.Video v : data) {
                boolean dup = false;
                for (Movie.Video old : quickSearchData) {
                    if (old.sourceKey != null && old.sourceKey.equals(v.sourceKey)
                            && old.id != null && old.id.equals(v.id)) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) quickSearchData.add(v);
            }
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH, data));
        }
    }

    private void insertVod(String sourceKey, VodInfo vodInfo) {
        // LOG.d(sourceKey + ":" + vodInfo.toString());
        try {
            vodInfo.playNote = vodInfo.seriesMap.get(vodInfo.playFlag).get(vodInfo.playIndex).name;
        } catch (Throwable th) {
            vodInfo.playNote = "";
        }
        RoomDataManger.insertVodRecord(sourceKey, vodInfo);
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (searchExecutorService != null) {
                searchExecutorService.shutdownNow();
                searchExecutorService = null;
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        OkGo.getInstance().cancelTag("fenci");
        OkGo.getInstance().cancelTag("detail");
        OkGo.getInstance().cancelTag("quick_search");
        EventBus.getDefault().unregister(this);
    }

    @Override
    public void onBackPressed() {
        if (fullWindows) {
            if (playFragment.onBackPressed())
                return;
            toggleFullPreview();
            List<VodInfo.VodSeries> list = vodInfo.seriesMap.get(vodInfo.playFlag);
            assert list != null;
            tvSeriesGroup.setVisibility(list.size()>1 ? View.VISIBLE : View.GONE);
            mGridView.requestFocus();
            return;
        }
        if (seriesSelect) {
            if (seriesFlagFocus != null && !seriesFlagFocus.isFocused()) {
                seriesFlagFocus.requestFocus();
                return;
            }
        }
        if(showPreview && playFragment!=null)playFragment.setPlayTitle(false);
        super.onBackPressed();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && playFragment != null && fullWindows) {
            if (playFragment.dispatchKeyEvent(event)) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event != null && playFragment != null && fullWindows) {
            if (playFragment.onKeyDown(keyCode,event)) {
                return true;
            }
        }
        // NOTE(2026-10-26 API15 port): 详情页遥控焦点导航不稳定（剧集网格会吸收上下键，
        // 「全屏」按钮经常抢不到焦点），加菜单键作为稳定入口——任意焦点直接切全屏。
        if (keyCode == KeyEvent.KEYCODE_MENU && showPreview && playFragment != null) {
            if (event != null && event.getRepeatCount() == 0) {
                toggleFullPreview();
                if (firstReverse) {
                    jumpToPlay();
                    firstReverse = false;
                }
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (event != null && playFragment != null && fullWindows) {
            if (playFragment.onKeyUp(keyCode,event)) {
                return true;
            }
        }
        // 菜单键已在 onKeyDown 里处理（切换全屏），这里吞掉 UP 事件，
        // 否则会再发一次 KEYCODE_MENU 被上层当换源键处理。
        if (keyCode == KeyEvent.KEYCODE_MENU && showPreview && playFragment != null) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }
    // preview
    VodInfo previewVodInfo = null;
    boolean fullWindows = false;
    ViewGroup.LayoutParams windowsPreview = null;
    ViewGroup.LayoutParams windowsFull = null;

    public void toggleFullPreview() {
        if (windowsPreview == null) {
            windowsPreview = llPlayerFragmentContainer.getLayoutParams();
        }
        if (windowsFull == null) {
            windowsFull = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        fullWindows = !fullWindows;
        llPlayerFragmentContainer.setLayoutParams(fullWindows ? windowsFull : windowsPreview);
        llPlayerFragmentContainerBlock.setVisibility(fullWindows ? View.GONE : View.VISIBLE);
        mGridView.setVisibility(fullWindows ? View.GONE : View.VISIBLE);
        mGridViewFlag.setVisibility(fullWindows ? View.GONE : View.VISIBLE);
        tvSeriesGroup.setVisibility(fullWindows ? View.GONE : View.VISIBLE);
        toggleSubtitleTextSize();
    }

    void toggleSubtitleTextSize() {
        int subtitleTextSize  = SubtitleHelper.getTextSize(this);
        if (!fullWindows) {
            subtitleTextSize *= 0.6;
        }
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SUBTITLE_SIZE_CHANGE, subtitleTextSize));
    }

    private void setTvPlayUrl(String url)
    {
        setTextShow(tvPlayUrl, "播放地址：", url);
    }

    public void use1DMDownload() {
        if (vodInfo != null && vodInfo.seriesMap.get(vodInfo.playFlag).size() > 0){
            VodInfo.VodSeries vod = vodInfo.getCurrentSeries();
            if (vod == null) return;
            String url = TextUtils.isEmpty(playFragment.getFinalUrl())?vod.url:playFragment.getFinalUrl();
            // 创建Intent对象，启动1DM App
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.setDataAndType(Uri.parse(url), "video/mp4");
            intent.putExtra("title", vodInfo.name+" "+vod.name); // 传入文件保存名
//            intent.setClassName("idm.internet.download.manager.plus", "idm.internet.download.manager.MainActivity");
            intent.setClassName("idm.internet.download.manager.plus", "idm.internet.download.manager.Downloader");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // 检查1DM App是否已安装
            PackageManager pm = getPackageManager();
            List<ResolveInfo> activities = pm.queryIntentActivities(intent, 0);
            boolean isIntentSafe = activities.size() > 0;

            if (isIntentSafe) {
                startActivity(intent); // 启动1DM App
            } else {
                // 如果1DM App未安装，提示用户安装1DM App
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("请先安装1DM+下载管理器");
                builder.setMessage("为了下载视频，请先安装1DM+下载管理器。\n点击确定跳转到浏览器...");
                builder.setPositiveButton("确定", (dialog, which) -> {
                    // 跳转到下载链接
                    Intent downloadIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.baidu.com/s?wd=1dm%2BAPP"));
                    startActivity(downloadIntent);
                });
                builder.setNegativeButton("取消", null);
                builder.show();
            }
        } else {
            Toast.makeText(mContext, "资源异常,请稍后重试", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        int aid = Hawk.get(HawkConfig.ACTIVITY_ID, -1);
        LOG.d("DetailActivity onPause, aid: " + aid);
        if (aid == 0) Hawk.put(HawkConfig.ACTIVITY_ID, 3);
    }
}
