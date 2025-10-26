package com.gmail.bobason01;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class SnapshotStore {
    public static void saveYamlGz(File file, YamlConfiguration y) throws IOException{
        if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
        try(OutputStream out = new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(file)))){
            out.write(y.saveToString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    public static YamlConfiguration loadYamlGz(File file) throws IOException{
        try(InputStream in = new BufferedInputStream(new GZIPInputStream(new FileInputStream(file)))){
            String s = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            YamlConfiguration y = new YamlConfiguration();
            try{ y.loadFromString(s); }catch(Exception e){ throw new IOException(e); }
            return y;
        }
    }
    public static void writeDelta(File file, Map<String,Object> map) throws IOException{
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String,Object> e : map.entrySet()) y.set(e.getKey(), e.getValue());
        saveYamlGz(file, y);
    }
}
