package org.dreamslab.screen;

import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads the online challenge list (an Excel file shared as
 * "Anyone with the link can view" in OneDrive or SharePoint) and returns the
 * first sheet as rows of text: {"ok":true,"rows":[["Show on screen","Challenge (English)",...],...]}.
 */
final class Sheet {
    private Sheet() { }

    static String fetchJson(String link) {
        try {
            byte[] data = download(downloadUrl(link.trim()));
            List<List<String>> rows;
            if (data.length > 2 && data[0] == 'P' && data[1] == 'K') {
                rows = readXlsx(data);
            } else {
                String head = new String(data, 0, Math.min(data.length, 400), StandardCharsets.UTF_8).toLowerCase();
                if (head.contains("<html")) return error("signin");
                rows = readCsv(new String(data, StandardCharsets.UTF_8));
            }
            JSONArray out = new JSONArray();
            for (List<String> r : rows) {
                JSONArray a = new JSONArray();
                for (String v : r) a.put(v);
                out.put(a);
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("rows", out);
            return o.toString();
        } catch (java.net.UnknownHostException e) {
            return error("offline");
        } catch (java.io.IOException e) {
            return error("download");
        } catch (Exception e) {
            return error("read");
        }
    }

    private static String error(String code) {
        return "{\"ok\":false,\"error\":\"" + code + "\"}";
    }

    /** OneDrive / SharePoint share links download the file itself when download=1 is added. */
    static String downloadUrl(String link) {
        if (link.contains("download=1")) return link;
        return link + (link.contains("?") ? "&" : "?") + "download=1";
    }

    private static byte[] download(String url) throws Exception {
        for (int hops = 0; hops < 8; hops++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) DREAMS-Screen");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String next = c.getHeaderField("Location");
                c.disconnect();
                if (next == null) throw new java.io.IOException("redirect");
                url = new URL(new URL(url), next).toString();
                continue;
            }
            if (code != 200) { c.disconnect(); throw new java.io.IOException("http " + code); }
            try (InputStream in = c.getInputStream()) {
                return readAll(in, 5 * 1024 * 1024);
            } finally {
                c.disconnect();
            }
        }
        throw new java.io.IOException("too many redirects");
    }

    private static byte[] readAll(InputStream in, int max) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > max) throw new java.io.IOException("too big");
        }
        return out.toByteArray();
    }

    // ---------- .xlsx: shared strings + the first worksheet ----------

    private static List<List<String>> readXlsx(byte[] data) throws Exception {
        Map<String, byte[]> parts = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName();
                if (n.equals("xl/sharedStrings.xml") || n.equals("xl/workbook.xml")
                    || n.equals("xl/_rels/workbook.xml.rels") || n.startsWith("xl/worksheets/sheet")) {
                    parts.put(n, readAll(z, 20 * 1024 * 1024));
                }
            }
        }
        List<String> strings = parts.containsKey("xl/sharedStrings.xml") ? sharedStrings(parts.get("xl/sharedStrings.xml")) : new ArrayList<String>();
        String sheet = firstSheetPath(parts);
        byte[] xml = parts.get(sheet);
        if (xml == null) throw new Exception("no sheet");
        return worksheet(xml, strings);
    }

    /** The first tab in the workbook's tab order (not always sheet1.xml). */
    private static String firstSheetPath(Map<String, byte[]> parts) throws Exception {
        byte[] wb = parts.get("xl/workbook.xml"), rels = parts.get("xl/_rels/workbook.xml.rels");
        if (wb != null && rels != null) {
            String rid = null;
            XmlPullParser p = Xml.newPullParser();
            p.setInput(new ByteArrayInputStream(wb), "UTF-8");
            for (int t = p.getEventType(); t != XmlPullParser.END_DOCUMENT && rid == null; t = p.next()) {
                if (t == XmlPullParser.START_TAG && "sheet".equals(p.getName())) {
                    for (int i = 0; i < p.getAttributeCount(); i++) {
                        if (p.getAttributeName(i).endsWith("id") && p.getAttributeName(i).startsWith("r")) rid = p.getAttributeValue(i);
                    }
                }
            }
            if (rid != null) {
                XmlPullParser q = Xml.newPullParser();
                q.setInput(new ByteArrayInputStream(rels), "UTF-8");
                for (int t = q.getEventType(); t != XmlPullParser.END_DOCUMENT; t = q.next()) {
                    if (t == XmlPullParser.START_TAG && "Relationship".equals(q.getName()) && rid.equals(q.getAttributeValue(null, "Id"))) {
                        String target = q.getAttributeValue(null, "Target");
                        if (target.startsWith("/")) target = target.substring(1);
                        else target = "xl/" + target;
                        if (parts.containsKey(target)) return target;
                    }
                }
            }
        }
        return "xl/worksheets/sheet1.xml";
    }

    private static List<String> sharedStrings(byte[] xml) throws Exception {
        List<String> out = new ArrayList<>();
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new ByteArrayInputStream(xml), "UTF-8");
        StringBuilder cur = null;
        boolean inT = false, inRph = false;
        for (int t = p.getEventType(); t != XmlPullParser.END_DOCUMENT; t = p.next()) {
            if (t == XmlPullParser.START_TAG) {
                String n = p.getName();
                if ("si".equals(n)) cur = new StringBuilder();
                else if ("rPh".equals(n)) inRph = true;
                else if ("t".equals(n)) inT = true;
            } else if (t == XmlPullParser.TEXT) {
                if (inT && !inRph && cur != null) cur.append(p.getText());
            } else if (t == XmlPullParser.END_TAG) {
                String n = p.getName();
                if ("si".equals(n)) { out.add(cur == null ? "" : cur.toString()); cur = null; }
                else if ("rPh".equals(n)) inRph = false;
                else if ("t".equals(n)) inT = false;
            }
        }
        return out;
    }

    private static List<List<String>> worksheet(byte[] xml, List<String> strings) throws Exception {
        TreeMap<Integer, TreeMap<Integer, String>> grid = new TreeMap<>();
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new ByteArrayInputStream(xml), "UTF-8");
        String ref = null, type = null;
        StringBuilder val = null;
        boolean inV = false, inT = false;
        for (int t = p.getEventType(); t != XmlPullParser.END_DOCUMENT; t = p.next()) {
            if (t == XmlPullParser.START_TAG) {
                String n = p.getName();
                if ("c".equals(n)) { ref = p.getAttributeValue(null, "r"); type = p.getAttributeValue(null, "t"); val = new StringBuilder(); }
                else if ("v".equals(n)) inV = true;
                else if ("t".equals(n)) inT = true;
            } else if (t == XmlPullParser.TEXT) {
                if ((inV || inT) && val != null) val.append(p.getText());
            } else if (t == XmlPullParser.END_TAG) {
                String n = p.getName();
                if ("v".equals(n)) inV = false;
                else if ("t".equals(n)) inT = false;
                else if ("c".equals(n) && ref != null && val != null) {
                    String v = val.toString();
                    if ("s".equals(type)) {
                        try { v = strings.get(Integer.parseInt(v.trim())); } catch (Exception ignored) { v = ""; }
                    } else if ("b".equals(type)) {
                        v = "1".equals(v.trim()) ? "TRUE" : "FALSE";
                    }
                    int[] rc = cell(ref);
                    if (rc != null && !v.trim().isEmpty()) {
                        TreeMap<Integer, String> row = grid.get(rc[0]);
                        if (row == null) { row = new TreeMap<>(); grid.put(rc[0], row); }
                        row.put(rc[1], v.trim());
                    }
                    ref = null; val = null;
                }
            }
        }
        List<List<String>> rows = new ArrayList<>();
        for (TreeMap<Integer, String> r : grid.values()) {
            List<String> row = new ArrayList<>();
            int last = r.lastKey();
            for (int c = 0; c <= last; c++) row.add(r.containsKey(c) ? r.get(c) : "");
            rows.add(row);
            if (rows.size() >= 500) break;
        }
        return rows;
    }

    /** "C12" -> {11, 2} (zero-based row, column). */
    private static int[] cell(String ref) {
        int i = 0, col = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
            col = col * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
            i++;
        }
        try { return new int[]{Integer.parseInt(ref.substring(i)) - 1, col - 1}; }
        catch (Exception e) { return null; }
    }

    // ---------- .csv (in case someone shares a CSV instead) ----------

    private static List<List<String>> readCsv(String s) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean q = false;
        if (s.startsWith("﻿")) s = s.substring(1);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (q) {
                if (ch == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') { f.append('"'); i++; }
                    else q = false;
                } else f.append(ch);
            } else if (ch == '"') q = true;
            else if (ch == ',') { row.add(f.toString().trim()); f.setLength(0); }
            else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
                row.add(f.toString().trim()); f.setLength(0);
                rows.add(row); row = new ArrayList<>();
                if (rows.size() >= 500) break;
            } else f.append(ch);
        }
        if (f.length() > 0 || !row.isEmpty()) { row.add(f.toString().trim()); rows.add(row); }
        return rows;
    }
}
