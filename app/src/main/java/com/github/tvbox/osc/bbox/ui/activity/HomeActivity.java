package com.github.tvbox.osc.bbox.ui.activity;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.IntEvaluator;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.*;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.app.ActivityCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.ViewPager;
import com.github.tvbox.osc.bbox.R;
import com.github.tvbox.osc.bbox.api.ApiConfig;
import com.github.tvbox.osc.bbox.base.BaseActivity;
import com.github.tvbox.osc.bbox.base.BaseLazyFragment;
import com.github.tvbox.osc.bbox.bean.AbsSortXml;
import com.github.tvbox.osc.bbox.bean.MovieSort;
import com.github.tvbox.osc.bbox.bean.SourceBean;
import com.github.tvbox.osc.bbox.event.RefreshEvent;
import com.github.tvbox.osc.bbox.server.ControlManager;
import com.github.tvbox.osc.bbox.ui.adapter.HomePageAdapter;
import com.github.tvbox.osc.bbox.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.bbox.ui.adapter.SortAdapter;
import com.github.tvbox.osc.bbox.ui.dialog.SelectDialog;
import com.github.tvbox.osc.bbox.ui.dialog.TipDialog;
import com.github.tvbox.osc.bbox.ui.fragment.GridFragment;
import com.github.tvbox.osc.bbox.ui.fragment.UserFragment;
import com.github.tvbox.osc.bbox.ui.tv.widget.DefaultTransformer;
import com.github.tvbox.osc.bbox.ui.tv.widget.FixedSpeedScroller;
import com.github.tvbox.osc.bbox.ui.tv.widget.NoScrollViewPager;
import com.github.tvbox.osc.bbox.ui.tv.widget.ViewObj;
import com.github.tvbox.osc.bbox.util.*;
import com.github.tvbox.osc.bbox.viewmodel.SourceViewModel;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;
import me.jessyan.autosize.utils.AutoSizeUtils;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

public class HomeActivity extends BaseActivity {
    private float height = 0f;

    private LinearLayout topLayout;
    private LinearLayout contentLayout;
    private TextView tvDate;
    private TextView tvName;
    private ImageView tvSetting;
    private TvRecyclerView mGridView;
    private NoScrollViewPager mViewPager;
    private SourceViewModel sourceViewModel;
    private SortAdapter sortAdapter;
    private HomePageAdapter pageAdapter;
    private View currentView;
    private final List<BaseLazyFragment> fragments = new ArrayList<>();
    private boolean isDownOrUp = false;
    private boolean sortChange = false;
    private int currentSelected = 0;
    private int sortFocused = 0;
    public View sortFocusView = null;
    private final Handler mHandler = new Handler();
    private long mExitTime = 0;
    private final Runnable mRunnable = new Runnable() {
        @SuppressLint({"DefaultLocale", "SetTextI18n"})
        @Override
        public void run() {
            Date date = new Date();
            @SuppressLint("SimpleDateFormat")
            SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm");
            tvDate.setText(timeFormat.format(date));
            mHandler.postDelayed(this, 1000);
        }
    };
    private static Resources res;
    public static Resources getRes() {
        return res;
    }

    @Override
    protected void onCreate(@Nullable @org.jetbrains.annotations.Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RECREATING = false; // 新实例已就绪，恢复「首页销毁=退出」默认语义
        verifyPermissions(this);// 动态申请权限
        // NOTE(2026-10-25): DLNA 接收端默认关闭（设置里手动开启才启动）——
        // 它常驻 jetty+cling 约 20MB 内存并占用 SSDP 组播端口，1GB 机器上按需启用
        if (Hawk.get(HawkConfig.DLNA_ENABLE, false)) {
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        startService(new Intent(HomeActivity.this, com.github.tvbox.osc.bbox.dlna.DlnaRendererService.class));
                    } catch (Throwable th) {
                        com.github.tvbox.osc.bbox.util.LOG.e("DLNA service start: " + th);
                    }
                }
            }, 3000);
        }
    }

    private static final int REQUEST_EXTERNAL_STORAGE = 1;
    private static String[] PERMISSIONS_STORAGE = {
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE"};


    // 在onCreate()方法中调用该方法即可
    public void verifyPermissions(Activity activity) {

        try {
            // 检测是否有写的权限
            int rwPermission = ActivityCompat.checkSelfPermission(activity,
                    "android.permission.WRITE_EXTERNAL_STORAGE");
            if (rwPermission != PackageManager.PERMISSION_GRANTED) {
                // 没有写的权限，去申请写的权限，会弹出对话框
                ActivityCompat.requestPermissions(activity, PERMISSIONS_STORAGE, REQUEST_EXTERNAL_STORAGE);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    @Override
    protected int getLayoutResID() {
        return R.layout.activity_home;
    }

    boolean useCacheConfig = false;

    @Override
    protected void init() {
        // takagen99: Added to allow read string
        res = getResources();

        EventBus.getDefault().register(this);
        ControlManager.get().startServer();
        initView();
        initViewModel();
        useCacheConfig = false;
        Intent intent = getIntent();
        if (intent != null && intent.getExtras() != null) {
            Bundle bundle = intent.getExtras();
            useCacheConfig = bundle.getBoolean("useCache", false);
        }
        initData();
    }

    private View.OnFocusChangeListener focusChangeListener = (v, hasFocus) -> {
        if (hasFocus)
            // v.animate().rotation(180f).setDuration(100).setInterpolator(new BounceInterpolator()).start();
            v.animate().rotation(60f).setDuration(100).setInterpolator(new LinearInterpolator()).start();
        else
            // v.animate().rotation(0f).setDuration(100).setInterpolator(new BounceInterpolator()).start();
            v.animate().rotation(0f).setDuration(100).setInterpolator(new LinearInterpolator()).start();
    };

    private void initView() {
        this.tvSetting = findViewById(R.id.setting);
        this.topLayout = findViewById(R.id.topLayout);
        this.tvDate = findViewById(R.id.tvDate);
        this.tvName = findViewById(R.id.tvName);
        this.contentLayout = findViewById(R.id.contentLayout);
        this.mGridView = findViewById(R.id.mGridView);
        this.mViewPager = findViewById(R.id.mViewPager);
        this.sortAdapter = new SortAdapter();
        this.mGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 0, false));
        this.mGridView.setSpacingWithMargins(0, AutoSizeUtils.dp2px(this.mContext, 10.0f));
        this.mGridView.setAdapter(this.sortAdapter);
        sortAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                mGridView.post(() -> {
                    View firstChild = Objects.requireNonNull(mGridView.getLayoutManager()).findViewByPosition(0);
                    if (firstChild != null) {
                        mGridView.setSelectedPosition(0);
                        firstChild.requestFocus();
                    }
                });
            }
        });
        this.mGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            public void onItemPreSelected(TvRecyclerView tvRecyclerView, View view, int position) {
                if (view != null && !HomeActivity.this.isDownOrUp) {
                    mHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            TextView textView = view.findViewById(R.id.tvTitle);
                            textView.getPaint().setFakeBoldText(false);
                            if (sortFocused == p) {
                                view.animate().scaleX(1.1f).scaleY(1.1f).setInterpolator(new BounceInterpolator()).setDuration(300).start();
                                textView.setTextColor(HomeActivity.this.getResources().getColor(R.color.color_FFFFFF));
                            } else {
                                view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(300).start();
                                textView.setTextColor(HomeActivity.this.getResources().getColor(R.color.color_BBFFFFFF));
                                view.findViewById(R.id.tvFilter).setVisibility(View.GONE);
                                view.findViewById(R.id.tvFilterColor).setVisibility(View.GONE);
                            }
                            textView.invalidate();
                        }

                        public final int p = position;
                    }, 10);
                }
            }

            public void onItemSelected(TvRecyclerView tvRecyclerView, View view, int position) {
                if (view == null) return;
                // NOTE(2026-10-26): 边界检查必须在任何动画/状态更新之前——
                // 之前写在 scaleX(1.1f) 之后提前 return，导致 sortFocused 未更新，
                // onItemPreSelected 里旧栏目 sortFocused==p 仍成立，旧栏目缩不回 1.0（显示变大且不恢复）
                if (position < 0) return;
                HomeActivity.this.currentView = view;
                HomeActivity.this.isDownOrUp = false;
                HomeActivity.this.sortChange = true;
                // 先把旧栏目的缩放显式复位，避免它依赖 sortFocused 判定
                if (sortFocusView != null && sortFocusView != view) {
                    sortFocusView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(300).start();
                    TextView oldTitle = sortFocusView.findViewById(R.id.tvTitle);
                    if (oldTitle != null) {
                        oldTitle.getPaint().setFakeBoldText(false);
                        oldTitle.setTextColor(HomeActivity.this.getResources().getColor(R.color.color_BBFFFFFF));
                        oldTitle.invalidate();
                    }
                }
                HomeActivity.this.sortFocusView = view;
                HomeActivity.this.sortFocused = position;
                view.animate().scaleX(1.1f).scaleY(1.1f).setInterpolator(new BounceInterpolator()).setDuration(300).start();
                TextView textView = view.findViewById(R.id.tvTitle);
                if (textView != null) {
                    textView.getPaint().setFakeBoldText(true);
                    textView.setTextColor(HomeActivity.this.getResources().getColor(R.color.color_FFFFFF));
                    textView.invalidate();
                }
                if (sortAdapter != null && position < sortAdapter.getItemCount()) {
                    MovieSort.SortData sortData = sortAdapter.getItem(position);
                    if (sortData != null && sortData.filters != null && !sortData.filters.isEmpty()) {
                        showFilterIcon(sortData.filterSelectCount());
                    }
                }
                mHandler.removeCallbacks(mDataRunnable);
                mHandler.postDelayed(mDataRunnable, 200);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                if (itemView != null && currentSelected == position
                        && currentSelected >= 0 && currentSelected < fragments.size()
                        && sortAdapter != null && position < sortAdapter.getItemCount()) {
                    BaseLazyFragment baseLazyFragment = fragments.get(currentSelected);
                    if ((baseLazyFragment instanceof GridFragment) && !sortAdapter.getItem(position).filters.isEmpty()) {// 弹出筛选
                        ((GridFragment) baseLazyFragment).showFilter();
                    } else if (baseLazyFragment instanceof UserFragment) {
                        showSiteSwitch();
                    }
                }
            }
        });

        this.mGridView.setOnInBorderKeyEventListener(new TvRecyclerView.OnInBorderKeyEventListener() {
            public boolean onInBorderKeyEvent(int direction, View view) {
                if (direction == View.FOCUS_UP) {
                    if (sortFocused < 0 || sortFocused >= fragments.size()) return false;
                    BaseLazyFragment baseLazyFragment = fragments.get(sortFocused);
                    if ((baseLazyFragment instanceof GridFragment)) {
                        ((GridFragment) baseLazyFragment).forceRefresh();
                    }
                }
                if (direction != View.FOCUS_DOWN) {
                    return false;
                }
                if (sortFocused < 0 || sortFocused >= fragments.size()) return false;
                BaseLazyFragment baseLazyFragment = fragments.get(sortFocused);
                if (!(baseLazyFragment instanceof GridFragment)) {
                    return false;
                }
                return !((GridFragment) baseLazyFragment).isLoad();
            }
        });

        tvSetting.clearFocus();
        tvSetting.setOnFocusChangeListener(focusChangeListener);
        tvSetting.setOnClickListener(view -> {
            FastClickCheckUtil.check(view, 500);
            jumpActivity(SettingActivity.class);
        });
        // Button : Settings >> To go into App Settings ----------------
        tvSetting.setOnLongClickListener(view -> {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null)));
            return true;
        });
        tvName.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // dataInitOk = false;
                // jarInitOk = true;
                // showSiteSwitch();
                if (dataInitOk && jarInitOk) {
                    FastClickCheckUtil.check(v, 1000);
                    Toast.makeText(mContext, "加载应用列表中...", Toast.LENGTH_SHORT).show();
                    jumpActivity(AppsActivity.class);
                } else {
                    FastClickCheckUtil.check(v, 1000);
                    jumpActivity(SettingActivity.class);
                }
            }
        });
        tvName.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (dataInitOk && jarInitOk) {
                    Intent intent = new Intent(getApplicationContext(), HomeActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    Bundle bundle = new Bundle();
                    bundle.putBoolean("useCache", true);
                    intent.putExtras(bundle);
                    HomeActivity.this.startActivity(intent);
                } else {
                    jumpActivity(SettingActivity.class);
                }
                return true;
            }
        });
        setLoadSir(this.contentLayout);
        // mHandler.postDelayed(mFindFocus, 500);


        ViewTreeObserver vto = topLayout.getViewTreeObserver();
        vto.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                // width = tvSetting.getMeasuredWidth()*1f;
                if (vto.isAlive()) {
                    vto.removeOnPreDrawListener(this);
                }
                //一定要返回true，返回false会不进行绘制，界面空白
                return true;
            }
        });
    }


    private boolean skipNextUpdate = false;

    private void initViewModel() {
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.sortResult.observe(this, absXml -> {
            if (skipNextUpdate) {
                skipNextUpdate = false;
                return;
            }
            showSuccess();
            if (absXml != null && absXml.classes != null && absXml.classes.sortList != null) {
                sortAdapter.setNewData(DefaultConfig.adjustSort(ApiConfig.get().getHomeSourceBean().getKey(), absXml.classes.sortList, true));
            } else {
                sortAdapter.setNewData(DefaultConfig.adjustSort(ApiConfig.get().getHomeSourceBean().getKey(), new ArrayList<>(), true));
            }
            initViewPager(absXml);
            SourceBean home = ApiConfig.get().getHomeSourceBean();
            if (home != null && home.getName() != null && !home.getName().isEmpty()) tvName.setText(home.getName());
            tvName.clearAnimation();
        });
    }

    private boolean dataInitOk = false;
    private boolean jarInitOk = false;

    private void initData() {
        SourceBean home = ApiConfig.get().getHomeSourceBean();
        if (home != null && home.getName() != null && !home.getName().isEmpty())
            tvName.setText(home.getName());
        // 默认给主页按钮焦点
        mGridView.requestFocus();
        if (dataInitOk && jarInitOk) {
            showLoading();
            sourceViewModel.getSort(ApiConfig.get().getHomeSourceBean().getKey());
            if (hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                LOG.i("有");
            } else {
                LOG.i("无");
            }
            if (!useCacheConfig && Hawk.get(HawkConfig.DEFAULT_LOAD_LIVE, false)) {
                jumpActivity(LivePlayActivity.class);
            }
            return;
        }
        tvNameAnimation();
        showLoading();
        if (dataInitOk && !jarInitOk) {
            if (!ApiConfig.get().getSpider().isEmpty()) {
                ApiConfig.get().loadJar(useCacheConfig, ApiConfig.get().getSpider(), new ApiConfig.LoadConfigCallback() {
                    @Override
                    public void success() {
                        jarInitOk = true;
                        mHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
//                                if (!useCacheConfig) Toast.makeText(HomeActivity.this, "自定义jar加载成功", Toast.LENGTH_SHORT).show();
                                initData();
                            }
                        }, 50);
                    }

                    @Override
                    public void notice(String msg) {
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(HomeActivity.this, msg, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }

                    @Override
                    public void error(String msg) {
                        jarInitOk = true;
                        dataInitOk = true;
                        mHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(HomeActivity.this, msg+"; 尝试加载最近一次的jar", Toast.LENGTH_SHORT).show();
                                initData();
                            }
                        },50);
                    }
                });
            }
            return;
        }
        ApiConfig.get().loadConfig(useCacheConfig, new ApiConfig.LoadConfigCallback() {
            TipDialog dialog = null;

            @Override
            public void notice(String msg) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(HomeActivity.this, msg, Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void success() {
                dataInitOk = true;
                if (ApiConfig.get().getSpider().isEmpty()) {
                    jarInitOk = true;
                }
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        initData();
                    }
                }, 50);
            }

            @Override
            public void error(String msg) {
                if (msg.equalsIgnoreCase("-1")) {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            dataInitOk = true;
                            jarInitOk = true;
                            initData();
                        }
                    });
                    return;
                }
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (dialog == null)
                            dialog = new TipDialog(HomeActivity.this, msg, "重试", "取消", new TipDialog.OnListener() {
                                @Override
                                public void left() {
                                    mHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            initData();
                                            dialog.hide();
                                        }
                                    });
                                }

                                @Override
                                public void right() {
                                    dataInitOk = true;
                                    jarInitOk = true;
                                    mHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            initData();
                                            dialog.hide();
                                        }
                                    });
                                }

                                @Override
                                public void cancel() {
                                    dataInitOk = true;
                                    jarInitOk = true;
                                    mHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            initData();
                                            dialog.hide();
                                        }
                                    });
                                }
                            });
                        if (!dialog.isShowing())
                            dialog.show();
                    }
                });
            }
        }, this);
    }

    private void initViewPager(AbsSortXml absXml) {
        if (sortAdapter.getData().size() > 0) {
            // NOTE(2026-10-26 API15 port): ★ 必须在追加前清空 fragments。
            // fragments 是成员级 List，而 initViewPager 会被调用多次：
            //   ① sortResult 观察者触发（切源重建后可能连发两次）
            //   ② initData 重入 / refreshEmpty / loadConfig 回调
            // 原来只 add 不清，栏目数量会 1→2→4→8 翻倍增长，
            // 表现就是用户反馈的「切过源之后，默认源后面多出一堆二级分类」。
            fragments.clear();
            for (MovieSort.SortData data : sortAdapter.getData()) {
                if (data.id.equals("my0")) {
                    if (Hawk.get(HawkConfig.HOME_REC, 0) == 1 && absXml != null && absXml.videoList != null && absXml.videoList.size() > 0) {
                        fragments.add(UserFragment.newInstance(absXml.videoList));
                    } else {
                        fragments.add(UserFragment.newInstance(null));
                    }
                } else {
                    fragments.add(GridFragment.newInstance(data));
                }
            }
            // 栏目数变了，selected/focused 索引必须夹到合法范围，否则 setCurrentItem 越界
            int n = sortAdapter.getData().size();
            if (currentSelected < 0 || currentSelected >= n) currentSelected = 0;
            if (sortFocused < 0 || sortFocused >= n) sortFocused = 0;
            pageAdapter = new HomePageAdapter(getSupportFragmentManager(), fragments);
            try {
                Field field = ViewPager.class.getDeclaredField("mScroller");
                field.setAccessible(true);
                FixedSpeedScroller scroller = new FixedSpeedScroller(mContext, new AccelerateInterpolator());
                field.set(mViewPager, scroller);
                scroller.setmDuration(300);
            } catch (Exception e) {
                LOG.e(e.getMessage());
            }
            mViewPager.setPageTransformer(true, new DefaultTransformer());
            mViewPager.setAdapter(pageAdapter);
            mViewPager.setCurrentItem(currentSelected, false);
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public void onBackPressed() {
        // 打断加载
        if (isLoading()) {
            refreshEmpty();
            return;
        }
        // 如果处于 VOD 删除模式，则退出该模式并刷新界面
        if (HawkConfig.hotVodDelete) {
            HawkConfig.hotVodDelete = false;
            UserFragment.homeHotVodAdapter.notifyDataSetChanged();
            return;
        }

        // 检查 fragments 状态
        if (this.fragments.size() <= 0 || this.sortFocused >= this.fragments.size() || this.sortFocused < 0) {
            doExit();
            return;
        }

        BaseLazyFragment baseLazyFragment = this.fragments.get(this.sortFocused);
        if (baseLazyFragment instanceof GridFragment) {
            GridFragment grid = (GridFragment) baseLazyFragment;
            // 如果当前 Fragment 能恢复之前保存的 UI 状态，则直接返回
            if (grid.restoreView()) {
                return;
            }
            // 如果 sortFocusView 存在且没有获取焦点，则请求焦点
            if (this.sortFocusView != null && !this.sortFocusView.isFocused()) {
                this.sortFocusView.requestFocus();
            }
            // 如果当前不是第一个界面，则将列表设置到第一项
            else if (this.sortFocused != 0) {
                this.mGridView.setSelection(0);
            } else {
                doExit();
            }
        } else if (baseLazyFragment instanceof UserFragment && UserFragment.tvHotList.canScrollVertically(-1)) {
            // 如果 UserFragment 列表可以向上滚动，则滚动到顶部
            UserFragment.tvHotList.scrollToPosition(0);
            this.mGridView.setSelection(0);
        } else {
            doExit();
        }
    }

    private void doExit() {
        // 如果两次返回间隔小于 2000 毫秒，则退出应用
        if (System.currentTimeMillis() - mExitTime < 2000) {
            AppManager.getInstance().finishAllActivity();
            EventBus.getDefault().unregister(this);
            ControlManager.get().stopServer();
            finish();
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        } else {
            // 否则仅提示用户，再按一次退出应用
            mExitTime = System.currentTimeMillis();
            Toast.makeText(mContext, "再按一次返回键退出应用", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.post(mRunnable);
    }


    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacksAndMessages(null);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_PUSH_URL) {
            if (ApiConfig.get().getSource("push_agent") != null) {
                Intent newIntent = new Intent(mContext, DetailActivity.class);
                newIntent.putExtra("id", (String) event.obj);
                newIntent.putExtra("sourceKey", "push_agent");
                newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                HomeActivity.this.startActivity(newIntent);
            }
        } else if (event.type == RefreshEvent.TYPE_FILTER_CHANGE) {
            if (currentView != null) {
                showFilterIcon((int) event.obj);
            }
        }
    }

    private void showFilterIcon(int count) {
        boolean visible = count > 0;
        currentView.findViewById(R.id.tvFilterColor).setVisibility(visible ? View.VISIBLE : View.GONE);
        currentView.findViewById(R.id.tvFilter).setVisibility(visible ? View.GONE : View.VISIBLE);
    }

    private final Runnable mDataRunnable = new Runnable() {
        @Override
        public void run() {
            if (sortChange) {
                sortChange = false;
                // NOTE(2026-10-25): 快速切栏目时 sortFocused 可能超出 fragment 数（数据未加载完）
                // 原实现直接 setCurrentItem(sortFocused) → IndexOutOfBounds 崩溃
                int n = (sortAdapter == null) ? 0 : sortAdapter.getItemCount();
                if (fragments.size() > 0 && n > 0 && sortFocused >= n) {
                    sortFocused = n - 1;
                }
                if (sortFocused < 0) {
                    sortFocused = 0;
                }
                if (sortFocused != currentSelected && fragments.size() > 0) {
                    currentSelected = sortFocused;
                    try {
                        mViewPager.setCurrentItem(sortFocused, false);
                    } catch (Throwable th) {
                        LOG.e("sort switch: " + th);
                    }
                    changeTop(sortFocused != 0);
                }
            }
        }
    };

    private long menuKeyDownTime = 0;
    private static final long LONG_PRESS_THRESHOLD = 2000; // 设置长按的阈值，单位是毫秒
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        // NOTE(2026-10-26): 原为 `if (topHide < 0) return false;` —— topHide 只有 0/1
        // （changeTop 里 hide?1:0），`< 0` 永不成立；但顶部栏收起时（topHide=1）
        // 菜单键仍应能呼出换源框，否则栏目页永远切不了源。菜单键单独放行。
        if (topHide < 0 && keyCode != KeyEvent.KEYCODE_MENU)
            return false;
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                menuKeyDownTime = System.currentTimeMillis();
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                long pressDuration = System.currentTimeMillis() - menuKeyDownTime;
                if (pressDuration >= LONG_PRESS_THRESHOLD) {
                    jumpActivity(SettingActivity.class);;
                }else {
                    showSiteSwitch();
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    byte topHide = 0;

    private void changeTop(boolean hide) {
        ViewObj viewObj = new ViewObj(topLayout, (ViewGroup.MarginLayoutParams) topLayout.getLayoutParams());
        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {

            }

            @Override
            public void onAnimationEnd(Animator animation) {
                topHide = (byte) (hide ? 1 : 0);
            }

            @Override
            public void onAnimationCancel(Animator animation) {

            }

            @Override
            public void onAnimationRepeat(Animator animation) {

            }
        });
        if (hide && topHide == 0) {
            animatorSet.playTogether(ObjectAnimator.ofObject(viewObj, "marginTop", new IntEvaluator(),
                            AutoSizeUtils.mm2px(this.mContext, 10.0f),
                            AutoSizeUtils.mm2px(this.mContext, 0.0f)),
                    ObjectAnimator.ofObject(viewObj, "height", new IntEvaluator(),
                            AutoSizeUtils.mm2px(this.mContext, 70f),
                            // AutoSizeUtils.mm2px(this.mContext, 50.0f),
                            AutoSizeUtils.mm2px(this.mContext, 1.0f)),
                    ObjectAnimator.ofFloat(this.topLayout, "alpha", 1.0f, 0.0f)
            );
            animatorSet.setDuration(200);
            animatorSet.start();
            tvSetting.setFocusable(false);
            return;
        }
        if (!hide && topHide == 1) {
            animatorSet.playTogether(ObjectAnimator.ofObject(viewObj, "marginTop", new IntEvaluator(),
                            AutoSizeUtils.mm2px(this.mContext, 0.0f),
                            AutoSizeUtils.mm2px(this.mContext, 10.0f)),
                    ObjectAnimator.ofObject(viewObj, "height", new IntEvaluator(),
                            AutoSizeUtils.mm2px(this.mContext, 1.0f),
                            AutoSizeUtils.mm2px(this.mContext, 70f)),
                    ObjectAnimator.ofFloat(this.topLayout, "alpha", 0.0f, 1.0f)
            );
            animatorSet.setDuration(200);
            animatorSet.start();
            tvSetting.setFocusable(true);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // NOTE(2026-10-25): 这里绝不能自杀进程——原版 appExit(0)=killProcess+System.exit 是历史遗留，
        // 任何 Activity 重建（换源/换片源/配置变化）都会触发 onDestroy → 进程被误杀（用户所见『关APP』）。
        // 用户主动退出由 doExit()（双按返回键）处理。
        EventBus.getDefault().unregister(this);
    }

    /** 换源/换片源走 CLEAR_TASK 重建，旧实例 onDestroy 时不得自杀进程（appExit） */
    public static volatile boolean RECREATING = false;

    private SelectDialog<SwitchItem> mSiteSwitchDialog;
    private SelectDialogAdapter<SwitchItem> mSiteSwitchAdapter;

    /** 菜单键切换列表条目：配置源（configUrl 非空）或当前配置内的站点 */
    public static class SwitchItem {
        public final String name;
        public final SourceBean site;
        public final String configName;
        public final String configUrl;

        SwitchItem(SourceBean s) { name = s.getName(); site = s; configName = null; configUrl = null; }
        SwitchItem(String n, String u) { name = n; site = null; configName = n; configUrl = u; }

        /**
         * 展示名：片源后附健康探测结果（如「虎牙资源  320ms」「牛牛资源  空响应 ✗」）。
         * 【源】配置项不探测——它们的可用性等于「本地文件是否存在」，恒可用。
         */
        String displayName() {
            if (site == null) return name;
            String h = com.github.tvbox.osc.bbox.util.SourceHealthChecker.label(site.getKey());
            return h.isEmpty() ? name : (name + "  " + h);
        }
    }

    /**
     * 对片源做健康探测：先显示已有缓存，后台补测未测过的，测完刷新弹框。
     * 只在弹框仍可见时刷新，避免用户已关闭还在动 UI。
     */
    private void startSourceHealthProbe(List<SourceBean> sites) {
        if (sites == null || sites.isEmpty()) return;
        final java.util.ArrayList<SourceBean> need = new java.util.ArrayList<>();
        for (SourceBean sb : sites) {
            if (com.github.tvbox.osc.bbox.util.SourceHealthChecker.getCached(sb.getKey()) == null) {
                need.add(sb);
            }
        }
        if (need.isEmpty()) return;
        com.github.tvbox.osc.bbox.util.SourceHealthChecker.checkAll(need,
                new com.github.tvbox.osc.bbox.util.SourceHealthChecker.OnResult() {
                    @Override
                    public void onOne(SourceBean bean, com.github.tvbox.osc.bbox.util.SourceHealthChecker.Health health) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                refreshSiteSwitchItems();
                            }
                        });
                    }

                    @Override
                    public void onDone() {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                refreshSiteSwitchItems();
                            }
                        });
                    }
                });
    }

    /** 探测出结果后刷新弹框条目文字（不重建弹框，避免焦点跳动） */
    private void refreshSiteSwitchItems() {
        try {
            if (mSiteSwitchDialog == null || !mSiteSwitchDialog.isShowing()) return;
            if (mSiteSwitchAdapter == null) return;
            // 只重设数据，不重新 show()：重建弹框会丢焦点，用户正在用遥控器选源时很跳
            java.util.ArrayList<SwitchItem> items = buildSiteSwitchItems();
            if (items.isEmpty()) return;
            // NOTE(2026-10-26 API15 port): 必须传**合法下标**，不能传 -1。
            // SelectDialogAdapter.onClick 里有 notifyItemChanged(select)，
            // select=-1 时会抛 IndexOutOfBoundsException。-1 表示"沿用上次选中"。
            int sel = mSiteSwitchSelect;
            if (sel < 0 || sel >= items.size()) sel = 0;
            mSiteSwitchAdapter.setData(items, sel);
        } catch (Throwable th) {
            LOG.e("refreshSiteSwitchItems: " + th);
        }
    }

    /** 当前选中项的原始下标（探测刷新后要还原，-1 表示不指定） */
    private int mSiteSwitchSelect = -1;

    /**
     * 组装换源列表：【源】配置项（本地文件，恒可用）+ 片源（按探测结果排序）。
     * 选中项下标按「key 身份」计算后写入 mSiteSwitchSelect。
     */
    private java.util.ArrayList<SwitchItem> buildSiteSwitchItems() {
        // NOTE(2026-10-25): 顶部插入内置配置源（API_MAP，file:// 本地零网络依赖），
        // 原先换配置源要 设置→线路配置→历史列表 三层，电视上不可发现
        java.util.ArrayList<SwitchItem> items = new java.util.ArrayList<>();
        ArrayList<String> nameHistory = Hawk.get(HawkConfig.API_NAME_HISTORY, new ArrayList<String>());
        HashMap<String, String> apiMap = Hawk.get(HawkConfig.API_MAP, new HashMap<String, String>());
        for (String n : nameHistory) {
            String u = apiMap.containsKey(n) ? apiMap.get(n) : n;
            items.add(new SwitchItem("【源】" + n, u));
        }
        List<SourceBean> sites = ApiConfig.get().getSwitchSourceBeanList();
        // NOTE(2026-10-26 API15 port): 已探测过的片源按「可用优先、延迟低的靠前」排序，
        // 让失效源（空响应/403）自然沉到列表后面——这是源测速最实用的价值。
        // 未探测过的排中间（不知道好坏，但不放到最前误导用户）。
        if (sites != null && sites.size() > 1) {
            final java.util.HashMap<String, Integer> rank = new java.util.HashMap<>();
            final java.util.HashMap<String, Long> costMap = new java.util.HashMap<>();
            for (SourceBean sb : sites) {
                com.github.tvbox.osc.bbox.util.SourceHealthChecker.Health h =
                        com.github.tvbox.osc.bbox.util.SourceHealthChecker.getCached(sb.getKey());
                if (h == null) {
                    rank.put(sb.getKey(), 1);        // 未探测
                } else if (h.ok) {
                    rank.put(sb.getKey(), 0);        // 可用
                    costMap.put(sb.getKey(), h.latencyMs);
                } else {
                    rank.put(sb.getKey(), 2);        // 失效
                }
            }
            try {
                java.util.Collections.sort(sites, new java.util.Comparator<SourceBean>() {
                    @Override
                    public int compare(SourceBean a, SourceBean b) {
                        int ra = rank.containsKey(a.getKey()) ? rank.get(a.getKey()) : 1;
                        int rb = rank.containsKey(b.getKey()) ? rank.get(b.getKey()) : 1;
                        if (ra != rb) return ra - rb;
                        long ca = costMap.containsKey(a.getKey()) ? costMap.get(a.getKey()) : 0;
                        long cb = costMap.containsKey(b.getKey()) ? costMap.get(b.getKey()) : 0;
                        return Long.compare(ca, cb);
                    }
                });
            } catch (Throwable th) {
                LOG.e("sort sites by health: " + th);
            }
        }
        if (sites != null) {
            for (SourceBean sb : sites) {
                items.add(new SwitchItem(sb));
            }
        }
        // NOTE(2026-10-26 API15 port): 选中项必须按「key 身份」定位，不能只靠下标。
        // 上面按探测结果重排了片源，若当前源是片源，它的下标会随排序变化，
        // 沿用旧下标会把高亮错打到别的源上（用户会以为选错了源）。
        // 顺序：当前配置源 → 当前站点 → 0。
        int sel = -1;
        String curApiKey = Hawk.get(HawkConfig.API_URL, "");
        for (int i = 0; i < items.size(); i++) {
            SwitchItem it = items.get(i);
            if (it.configUrl != null && it.configUrl.equals(curApiKey)) {
                sel = i;
                break;
            }
        }
        if (sel < 0) {
            SourceBean home = ApiConfig.get().getHomeSourceBean();
            if (home != null) {
                for (int i = 0; i < items.size(); i++) {
                    SwitchItem it = items.get(i);
                    if (it.site != null && home.getKey() != null && home.getKey().equals(it.site.getKey())) {
                        sel = i;
                        break;
                    }
                }
            }
        }
        mSiteSwitchSelect = (sel < 0) ? 0 : sel;
        return items;
    }

    void showSiteSwitch() {
        java.util.ArrayList<SwitchItem> items = buildSiteSwitchItems();
        if (items.isEmpty()) return;
        // buildSiteSwitchItems() 已把「当前选中项」写进 mSiteSwitchSelect（可能为 0）
        int select = mSiteSwitchSelect;
        if (select < 0 || select >= items.size()) select = 0;
        // NOTE(2026-10-26 API15 port): 对片源（非【源】配置项）做健康探测，
        // 把「320ms / 空响应 / 403 / 超时」直接标在名字后面，并按可用性排序。
        // 实测各源失效形态差异极大（搜索接口空壳响应、403），不标出来只能一个个试。
        // 先用缓存（不阻塞），再后台补测。
        try {
            startSourceHealthProbe(ApiConfig.get().getSwitchSourceBeanList());
        } catch (Throwable th) {
            LOG.e("startSourceHealthProbe: " + th);
        }
        if (mSiteSwitchDialog == null) {
            mSiteSwitchDialog = new SelectDialog<>(HomeActivity.this);
            TvRecyclerView tvRecyclerView = mSiteSwitchDialog.findViewById(R.id.list);
            // 根据 items 数量动态计算列数
            int spanCount = (int) Math.floor(items.size() / 20.0);
            spanCount = Math.min(spanCount, 2);
            tvRecyclerView.setLayoutManager(new V7GridLayoutManager(mSiteSwitchDialog.getContext(), spanCount + 1));
            // 设置对话框宽度
            ConstraintLayout cl_root = mSiteSwitchDialog.findViewById(R.id.cl_root);
            ViewGroup.LayoutParams clp = cl_root.getLayoutParams();
            clp.width = AutoSizeUtils.mm2px(mSiteSwitchDialog.getContext(), 380 + 200 * spanCount);
            mSiteSwitchDialog.setTip("选择配置源（【源】）/ 片源（附探测结果）");
            mSiteSwitchAdapter = new SelectDialogAdapter<>(new SelectDialogAdapter.SelectDialogInterface<SwitchItem>() {
                @Override
                public void click(SwitchItem value, int pos) {
                    if (value.site != null) {
                        ApiConfig.get().setSourceBean(value.site);
                        refreshHome();
                    } else {
                        switchConfig(value.configName, value.configUrl);
                    }
                }

                @Override
                public String getDisplay(SwitchItem val) {
                    return val.displayName();
                }
            }, new DiffUtil.ItemCallback<SwitchItem>() {
                @Override
                public boolean areItemsTheSame(@NonNull SwitchItem oldItem, @NonNull SwitchItem newItem) {
                    return oldItem == newItem;
                }

                @Override
                public boolean areContentsTheSame(@NonNull SwitchItem oldItem, @NonNull SwitchItem newItem) {
                    // 比展示名而非 name：探测结果到达后要能触发条目刷新
                    return oldItem.displayName().equals(newItem.displayName());
                }
            });
            TvRecyclerView list = mSiteSwitchDialog.findViewById(R.id.list);
            list.setAdapter(mSiteSwitchAdapter);
        }
        mSiteSwitchAdapter.setData(items, select);
        TvRecyclerView list = mSiteSwitchDialog.findViewById(R.id.list);
        list.setSelectedPosition(select);
        final int fs = select;
        list.post(new Runnable() {
            @Override
            public void run() {
                list.smoothScrollToPosition(fs);
                list.setSelectionWithSmooth(fs);
            }
        });
        mSiteSwitchDialog.show();
    }

    /** 切换配置源：写 API_NAME/API_URL 后整页重启并强制重载配置（useCache=false） */
    private void switchConfig(String name, String url) {
        RECREATING = true;
        Hawk.put(HawkConfig.API_NAME, name);
        Hawk.put(HawkConfig.API_URL, url);
        Intent intent = new Intent(getApplicationContext(), HomeActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Bundle bundle = new Bundle();
        bundle.putBoolean("useCache", false);
        intent.putExtras(bundle);
        HomeActivity.this.startActivity(intent);
    }

    private void refreshHome()
    {
        RECREATING = true;
        Intent intent = new Intent(getApplicationContext(), HomeActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Bundle bundle = new Bundle();
        bundle.putBoolean("useCache", true);
        intent.putExtras(bundle);
        HomeActivity.this.startActivity(intent);
    }

    private void refreshEmpty()
    {
        skipNextUpdate=true;
        showSuccess();
        sortAdapter.setNewData(DefaultConfig.adjustSort(ApiConfig.get().getHomeSourceBean().getKey(), new ArrayList<>(), true));
        initViewPager(null);
        tvName.clearAnimation();
    }

    private void tvNameAnimation()
    {
        AlphaAnimation blinkAnimation = new AlphaAnimation(0.0f, 1.0f);
        blinkAnimation.setDuration(500);
        blinkAnimation.setStartOffset(20);
        blinkAnimation.setRepeatMode(Animation.REVERSE);
        blinkAnimation.setRepeatCount(Animation.INFINITE);
        tvName.startAnimation(blinkAnimation);
    }
}
