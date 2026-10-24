package com.github.tvbox.osc.bbox.util;

import com.github.tvbox.osc.bbox.bean.AbsSortXml;
import com.github.tvbox.osc.bbox.bean.AbsXml;
import com.github.tvbox.osc.bbox.bean.Movie;
import com.github.tvbox.osc.bbox.bean.MovieSort;

import java.util.Iterator;

/**
 * NOTE(2026-10-24 API15 port): \u5185\u5bb9\u5408\u89c4\u8fc7\u6ee4\u2014\u2014\u4f26\u7406/\u60c5\u8272/\u798f\u5229\u7c7b\u76ee\u4e0e\u7247\u540d\u5168\u90e8\u62e6\u622a
 * \u5728 SourceViewModel \u7684\u56db\u4e2a\u89e3\u6790\u51fa\u53e3\uff08sortXml/sortJson/xml/json\uff09\u7edf\u4e00\u8c03\u7528
 */
public class ContentFilter {

    private static final String[] BLOCKED = {
            "\u4f26\u7406", "\u60c5\u8272", "\u798f\u5229", "\u65e0\u7801", "\u6709\u7801",
            "18\u7981", "\u5236\u670d\u8bf1\u60d1", "\u5de8\u4e73"
    };

    public static boolean blocked(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        for (String kw : BLOCKED) {
            if (lower.contains(kw)) return true;
        }
        return lower.contains("18+") || lower.contains("r18");
    }

    /** \u8fc7\u6ee4\u5206\u7c7b + \u7247\u5355\uff08\u542b\u641c\u7d22\u7ed3\u679c/\u63a8\u8350\u4f4d\uff09 */
    public static void apply(AbsXml data) {
        if (data == null) return;
        try {
            if (data.movie != null && data.movie.videoList != null) {
                Iterator<Movie.Video> it = data.movie.videoList.iterator();
                while (it.hasNext()) {
                    if (blocked(it.next().name)) it.remove();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** \u8fc7\u6ee4\u5206\u7c7b\uff08AbsSortXml \u8def\u5f84\uff09 */
    public static void apply(AbsSortXml data) {
        if (data == null) return;
        try {
            if (data.classes != null && data.classes.sortList != null) {
                Iterator<MovieSort.SortData> it = data.classes.sortList.iterator();
                while (it.hasNext()) {
                    if (blocked(it.next().name)) it.remove();
                }
            }
            if (data.videoList != null) {
                Iterator<Movie.Video> it = data.videoList.iterator();
                while (it.hasNext()) {
                    if (blocked(it.next().name)) it.remove();
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
