package com.github.tvbox.osc.bbox.util;

import android.content.res.AssetManager;
import com.github.tvbox.osc.bbox.base.App;
import com.github.tvbox.osc.bbox.bean.Epginfo;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

public class EpgUtil {

    private static JsonObject epgDoc = null;
    // NOTE(2026-10-26): 加上限——EPG 站点名可能产生大量变体 key，超过 2000 条时清空重建
    private static final int EPG_CACHE_LIMIT = 2000;
    private static HashMap<String, JsonObject> epgHashMap = new HashMap<>();

    public static void init() {
        if(epgDoc != null)
            return;
        try {
            AssetManager assetManager = App.getInstance().getAssets(); //获得assets资源管理器（assets中的文件无法直接访问，可以使用AssetManager访问）
            InputStreamReader inputStreamReader = new InputStreamReader(assetManager.open("epg_data.json"),"UTF-8"); //使用IO流读取json文件内容
            BufferedReader br = new BufferedReader(inputStreamReader);//使用字符高效流
            String line;
            StringBuilder builder = new StringBuilder();
            while ((line = br.readLine())!=null){
                builder.append(line);
            }
            br.close();
            inputStreamReader.close();
            if(!builder.toString().isEmpty()){
                epgDoc =  new Gson().fromJson(builder.toString(), (Type)JsonObject.class);// 从builder中读取了json中的数据。
                // NOTE(2026-10-26 API15 port): 原为裸 get("epgs")/get("name") ——
                // EPG 源返回结构变化（无 epgs 或项缺 name）时整段解析失败，EPG 全空。
                for (JsonElement opt : DefaultConfig.safeJsonArray(epgDoc, "epgs")) {
                    if (!opt.isJsonObject()) continue;
                    JsonObject obj = (JsonObject) opt;
                    String name = DefaultConfig.safeJsonString(obj, "name", "");
                    if (name.isEmpty()) continue;
                    String[] names  = name.split(",");
                    for (String string : names) {
                        // NOTE(2026-10-26 API15 port): EPG_CACHE_LIMIT 之前只声明未使用（假修复），
                        // 此处补齐真正的上限保护：超限先清空再重建，避免静态 map 无限增长。
                        if (epgHashMap.size() >= EPG_CACHE_LIMIT) {
                            LOG.e("EpgUtil: epgHashMap 达上限 " + EPG_CACHE_LIMIT + "，清空重建");
                            epgHashMap.clear();
                        }
                        epgHashMap.put(string,obj);
                    }
                }
                return;
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static String[] getEpgInfo(String channelName) {
        try {
            if(epgHashMap.containsKey(channelName)){
                JsonObject obj = epgHashMap.get(channelName);
                // NOTE(2026-10-26 API15 port): 缺 logo/epgid 时原裸 get 抛异常 → EPG 取不到
                return new String[] {
                        DefaultConfig.safeJsonString(obj, "logo", ""),
                        DefaultConfig.safeJsonString(obj, "epgid", "")
                };
            }
        }catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    public static List<Epginfo> getEpgInfoList(String paramString, Date date) throws JSONException {
        List<Epginfo> list = new ArrayList<>();
        if (paramString.contains("epg_data")) {
            final JSONArray jSONArray = new JSONObject(paramString).optJSONArray("epg_data");
            if (jSONArray != null)
                for (int b = 0; b < jSONArray.length(); b++) {
                    JSONObject jSONObject = jSONArray.getJSONObject(b);
                    Epginfo epgbcinfo = new Epginfo(date,jSONObject.optString("title"), date, jSONObject.optString("start"), jSONObject.optString("end"),b);
                    list.add(epgbcinfo);
                }
        }
        return list;
    }
}
