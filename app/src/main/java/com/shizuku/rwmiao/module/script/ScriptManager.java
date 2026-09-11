package com.shizuku.rwmiao.module.script;

import android.app.Activity;
import android.app.Fragment;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Toast;

import com.shizuku.rwmiao.module.RWmiaoModule;
import com.shizuku.rwmiao.module.SimulationLifecycle;
import com.shizuku.rwmiao.module.support.GameTickDispatcher;
import com.shizuku.rwmiao.ui.support.RuntimePanels;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import io.github.libxposed.api.XposedInterface;

import static com.shizuku.rwmiao.config.SettingsContract.KEY_SCRIPT_ENABLED_PREFIX;
import static com.shizuku.rwmiao.config.SettingsContract.KEY_SCRIPTS_MASTER;
import static com.shizuku.rwmiao.config.SettingsContract.PREFS_NAME;

public final class ScriptManager {
    private static final String TAG = "RWmiaoScript";
    private static final String IMPORT_TAG = "rwmiao.script.import";
    private static final int MAX_SOURCE_BYTES = 256 * 1024;
    private static final String SETTING_PREFIX="rwmiao_script_setting_";
    public static final class Record {
        public final String id, name, fileName, units;
        public final boolean enabled, hasSettings;
        Record(ScriptDefinition d, boolean enabled) {
            id=d.id; name=d.name; fileName=d.sourceName; units=join(d.unitTypes); this.enabled=enabled;hasSettings=!d.settings.isEmpty();
        }
        static String join(List<String> values) { StringBuilder b=new StringBuilder();for(String v:values){if(b.length()>0)b.append(", ");b.append(v);}return b.toString(); }
    }
    private static final class RuntimeScript {
        LuaProgram program;
        boolean runtimeDisabled;
        RuntimeScript(LuaProgram p){program=p;}
        ScriptDefinition definition(){return program.definition;}
    }

    private final RWmiaoModule host;
    private final ClassLoader loader;
    private final GameAdapter adapter;
    private final CommandGateway commands;
    private final LinkedHashMap<String,RuntimeScript> scripts=new LinkedHashMap<>();
    private final Map<String,LinkedHashSet<Long>> unitBindings=new HashMap<>();
    private final Map<String,Map<Long,String>> unitGroups=new HashMap<>();
    private long nextGroupId;
    private final Map<String,Boolean> enabledCache=new HashMap<>();
    private final Map<String,Map<String,Object>> settingCache=new HashMap<>();
    private final Object lock=new Object();
    private Context context;
    private File directory;
    private long directoryStamp;
    private volatile boolean loaded;
    private final SimulationLifecycle simulationLifecycle=new SimulationLifecycle();
    private volatile Runnable uiChanged;
    private volatile Boolean masterCache;
    private volatile boolean hookStateDirty;
    private final ArrayList<Method> tickMethods=new ArrayList<>();
    private final ArrayList<GameTickDispatcher.Registration> tickHooks=new ArrayList<>();
    private final ArrayList<RuntimeScript> dueScratch=new ArrayList<>();
    private final LinkedHashSet<String> requestedDataScratch=new LinkedHashSet<>();

    public ScriptManager(RWmiaoModule host,ClassLoader loader)throws Throwable{
        this.host=host;this.loader=loader;adapter=new GameAdapter(host,loader);commands=new CommandGateway(host,loader,adapter);
    }
    public void install()throws Throwable{
        try{if(ensureStorage())reload();}catch(Throwable ignored){}
        Class<?> queue=loader.loadClass(host.target("gameFramework.c"));
        Method single=host.findNoArgMethod(queue,"c"),multi=host.findNoArgMethod(queue,"d");
        if(single==null&&multi==null)throw new NoSuchMethodException("command queue tick c/d");
        if(single!=null)tickMethods.add(single);if(multi!=null&&multi!=single)tickMethods.add(multi);
        refreshSettings();
    }
    public synchronized void refreshSettings(){
        masterCache=null;
        boolean loadedNow=false;
        if(context==null||!loaded)try{if(ensureStorage()&&!loaded){reload();loadedNow=true;}}catch(Throwable ignored){}
        if(masterEnabled()&&hasRunnableBindings()){
            if(tickHooks.isEmpty())for(Method method:tickMethods)tickHooks.add(host.tickDispatcher().register(method,queue->{try{tick();}catch(Throwable t){Log.e(TAG,"script tick skipped",t);}}));
        }else{
            for(GameTickDispatcher.Registration handle:tickHooks)try{handle.close();}catch(Throwable ignored){}
            tickHooks.clear();
        }
        if(loadedNow)host.refreshScriptSelectionAction();
    }
    private void tick()throws Throwable{
        if(context==null&&!ensureStorage())return;if(!loaded&&directory!=null)reload();
        if(!masterEnabled())return;
        int currentTick=adapter.currentTick();
        SimulationLifecycle.Observation observation=simulationLifecycle.observe(
                currentTick,host.completedResyncGeneration());
        if(observation==SimulationLifecycle.Observation.INVALID
                ||observation==SimulationLifecycle.Observation.DUPLICATE)return;
        if(observation==SimulationLifecycle.Observation.NEW_MATCH)clearMatchState();
        else if(observation==SimulationLifecycle.Observation.RESYNC)prepareAfterResync();
        ArrayList<RuntimeScript> due=dueScratch;due.clear();
        LinkedHashSet<String> requestedData=requestedDataScratch;requestedData.clear();
        synchronized(lock){for(RuntimeScript runtime:scripts.values()){
            ScriptDefinition d=runtime.definition();if(runtime.runtimeDisabled||!isEnabled(d.id)
                    ||!hasEnabledUnits(d)||!runtime.program.due(currentTick))continue;
            due.add(runtime);
            requestedData.addAll(d.dataGroups);
        }}
        if(due.isEmpty()){if(hookStateDirty){hookStateDirty=false;refreshSettings();}return;}
        LinkedHashSet<Long> detailedUnits=null;boolean scoped=true;
        synchronized(lock){for(RuntimeScript runtime:due){ScriptDefinition d=runtime.definition();
            if(!d.dataGroups.contains("construction")){scoped=false;break;}
            for(String group:d.dataGroups)if(!constructionScopeGroup(group)){scoped=false;break;}
            if(!scoped)break;
        }if(scoped){detailedUnits=new LinkedHashSet<>();for(RuntimeScript runtime:due){LinkedHashSet<Long> ids=unitBindings.get(runtime.definition().id);if(ids!=null)detailedUnits.addAll(ids);}}}
        GameSnapshot snapshot=adapter.snapshot(requestedData,detailedUnits);if(snapshot.tick<0)return;
        synchronized(lock){java.util.Iterator<Map.Entry<String,LinkedHashSet<Long>>> it=unitBindings.entrySet().iterator();while(it.hasNext()){Map.Entry<String,LinkedHashSet<Long>> entry=it.next();LinkedHashSet<Long> bindings=entry.getValue();Map<Long,String> groups=unitGroups.get(entry.getKey());java.util.Iterator<Long> ids=bindings.iterator();while(ids.hasNext()){Long id=ids.next();UnitSnapshot unit=snapshot.get(id);if(unit==null||unit.relation!=0||unit.dead||unit.deleted){ids.remove();if(groups!=null)groups.remove(id);hookStateDirty=true;}}if(bindings.isEmpty()){it.remove();unitGroups.remove(entry.getKey());}}}
        for(RuntimeScript runtime:due){
            ScriptDefinition d=runtime.definition();
            synchronized(lock){if(scripts.get(d.id)!=runtime||runtime.runtimeDisabled||!isEnabled(d.id)||!hasEnabledUnits(d))continue;}
            try{runtime.program.tick(snapshot,commands,new LuaProgram.UnitPolicy(){public boolean enabled(UnitSnapshot u){return unitEnabled(d,u.id);}public void finish(long id){finishUnit(d,id);}public String group(long id){return unitGroup(d,id);}public void exit(String message){exitScript(d,message);}},settingsFor(d));}
            catch(Throwable t){Log.e(TAG,"Lua callback failed: "+d.id,t);if(t.getMessage()!=null&&t.getMessage().contains("连续 3 次")){synchronized(lock){runtime.runtimeDisabled=true;}hookStateDirty=true;Log.e(TAG,"Lua script auto-disabled for this match: "+d.id);}}
        }
        if(hookStateDirty){hookStateDirty=false;refreshSettings();}
    }

    private static boolean constructionScopeGroup(String group){
        return "identity".equals(group)||"team".equals(group)||"position".equals(group)
                ||"movement".equals(group)||"orders".equals(group)||"pathing".equals(group)
                ||"construction".equals(group);
    }

    public boolean masterEnabled(){if(context==null)return false;Boolean cached=masterCache;if(cached==null){cached=preferences().getBoolean(KEY_SCRIPTS_MASTER,false);masterCache=cached;}return cached;}
    public void setMasterEnabled(boolean enabled){masterCache=enabled;if(context!=null)preferences().edit().putBoolean(KEY_SCRIPTS_MASTER,enabled).apply();refreshSettings();host.refreshScriptSelectionAction();}
    public boolean isEnabled(String id){if(context==null)return true;synchronized(lock){Boolean cached=enabledCache.get(id);if(cached!=null)return cached;boolean enabled=preferences().getBoolean(KEY_SCRIPT_ENABLED_PREFIX+id,true);enabledCache.put(id,enabled);return enabled;}}
    public void setEnabled(String id,boolean enabled){synchronized(lock){enabledCache.put(id,enabled);if(!enabled){unitBindings.remove(id);unitGroups.remove(id);}}if(context!=null)preferences().edit().putBoolean(KEY_SCRIPT_ENABLED_PREFIX+id,enabled).apply();refreshSettings();host.refreshScriptSelectionAction();}
    public boolean resume(String id){synchronized(lock){RuntimeScript runtime=scripts.get(id);if(runtime==null)return false;runtime.program.resume();return true;}}
    public List<Record> records(){synchronized(lock){ArrayList<Record> out=new ArrayList<>();for(RuntimeScript r:scripts.values())out.add(new Record(r.definition(),isEnabled(r.definition().id)));return out;}}
    public void delete(String id){
        if(id==null||id.isEmpty())return;
        try{
            RuntimeScript removed;synchronized(lock){removed=scripts.get(id);}if(removed==null)return;
            if(directory==null&&!ensureStorage())throw new IOException("脚本目录尚未就绪");
            File file=new File(directory,removed.definition().sourceName);
            if(file.isFile()&&!file.delete())throw new IOException("无法删除脚本文件："+file.getName());
            synchronized(lock){
                RuntimeScript current=scripts.get(id);if(current!=null&&current.definition().sourceName.equals(removed.definition().sourceName))scripts.remove(id);
                unitBindings.remove(id);unitGroups.remove(id);enabledCache.remove(id);settingCache.remove(id);
            }
            if(context!=null){SharedPreferences.Editor editor=preferences().edit().remove(KEY_SCRIPT_ENABLED_PREFIX+id);for(ScriptSetting setting:removed.definition().settings)editor.remove(settingKey(id,setting.key));editor.apply();}
            directoryStamp=Math.max(directory.lastModified(),0L);
        }catch(Throwable t){Log.e(TAG,"Unable to delete script: "+id,t);}
        finally{
            try{refreshSettings();}catch(Throwable t){Log.e(TAG,"Unable to refresh script hooks after delete",t);}
            host.refreshScriptSelectionAction();notifyUiChanged();
        }
    }

    public void openSettings(Activity activity,String id){
        if(activity==null||activity.isFinishing())return;RuntimeScript runtime;
        synchronized(lock){runtime=scripts.get(id);}if(runtime==null||runtime.definition().settings.isEmpty()||!isEnabled(id))return;
        ScriptDefinition d=runtime.definition();ArrayList<RuntimePanels.ScriptSettingRow> rows=new ArrayList<>();
        for(ScriptSetting setting:d.settings){ArrayList<RuntimePanels.ScriptSettingOption> options=new ArrayList<>();for(ScriptSetting.Option option:setting.options)options.add(new RuntimePanels.ScriptSettingOption(option.value,option.label));
            rows.add(new RuntimePanels.ScriptSettingRow(setting.key,setting.name,setting.description,setting.type,setting.component,rawSetting(d,setting),setting.min,setting.max,setting.step,options));}
        RuntimePanels.showScriptSettings(activity,d.name,rows,values->{
            SharedPreferences.Editor editor=preferences().edit();for(int i=0;i<d.settings.size()&&i<values.length;i++){ScriptSetting setting=d.settings.get(i);try{editor.putString(settingKey(d.id,setting.key),setting.normalize(values[i]));}catch(IOException e){Log.w(TAG,"Rejected setting "+setting.key,e);}}editor.apply();synchronized(lock){settingCache.remove(d.id);}
        });
    }

    private Map<String,Object> settingsFor(ScriptDefinition d){Map<String,Object> cached;synchronized(lock){cached=settingCache.get(d.id);}if(cached!=null)return cached;LinkedHashMap<String,Object> out=new LinkedHashMap<>();for(ScriptSetting setting:d.settings){String value=rawSetting(d,setting);Object typed=value;if(ScriptSetting.BOOLEAN.equals(setting.type))typed=Boolean.parseBoolean(value);else if(ScriptSetting.NUMBER.equals(setting.type))try{typed=Double.parseDouble(value);}catch(Throwable ignored){typed=0d;}out.put(setting.key,typed);}Map<String,Object> created=Collections.unmodifiableMap(out);synchronized(lock){cached=settingCache.get(d.id);if(cached==null){settingCache.put(d.id,created);cached=created;}}return cached;}
    private String rawSetting(ScriptDefinition d,ScriptSetting setting){return context==null?setting.defaultValue:preferences().getString(settingKey(d.id,setting.key),setting.defaultValue);}
    private static String settingKey(String id,String key){return SETTING_PREFIX+id+"_"+key;}

    public void beginImport(Activity activity,Runnable changed){
        if(changed!=null)uiChanged=changed;if(context==null){context=activity;directory=new File(context.getFilesDir(),"rwmiao/scripts");if(!directory.isDirectory())directory.mkdirs();try{reload();}catch(Throwable t){Log.e(TAG,"Unable to initialize script storage",t);}}
        ImportFragment fragment=(ImportFragment)activity.getFragmentManager().findFragmentByTag(IMPORT_TAG);
        if(fragment==null){fragment=new ImportFragment();activity.getFragmentManager().beginTransaction().add(fragment,IMPORT_TAG).commitAllowingStateLoss();activity.getFragmentManager().executePendingTransactions();}
        fragment.manager=this;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);fragment.startActivityForResult(intent,ImportFragment.REQUEST);
    }
    void importUris(Activity activity,List<Uri> uris){if(activity==null||uris==null||uris.isEmpty())return;int success=0;ArrayList<String> errors=new ArrayList<>();ArrayList<String> enabledIds=new ArrayList<>();
        try{if(!ensureStorage())throw new IOException("脚本目录尚未就绪");int count=Math.min(32,uris.size());for(int i=0;i<count;i++){Uri uri=uris.get(i);String display=queryName(activity,uri);if(display==null)display=uri.getLastPathSegment();try{
                try(InputStream input=activity.getContentResolver().openInputStream(uri)){if(input==null)throw new IOException("无法打开文件");String source=read(input);LuaProgram checked=LuaProgram.compile(source,display);String fileName=checked.definition.id+".lua";File temp=new File(directory,fileName+".tmp");write(temp,source);LuaProgram.compile(read(new FileInputStream(temp)),fileName);File target=new File(directory,fileName);if(target.exists()&&!target.delete())throw new IOException("无法替换旧脚本");if(!temp.renameTo(target))throw new IOException("无法保存脚本");enabledIds.add(checked.definition.id);success++;}
            }catch(Throwable t){Log.e(TAG,"Import failed: "+display,t);errors.add((display==null?"未知文件":display)+"："+rootMessage(t));}}
            if(uris.size()>32)errors.add("一次最多导入 32 个文件");if(success>0){reload();SharedPreferences.Editor editor=preferences().edit();for(String id:enabledIds){synchronized(lock){enabledCache.put(id,true);}editor.putBoolean(KEY_SCRIPT_ENABLED_PREFIX+id,true);}editor.apply();refreshSettings();host.refreshScriptSelectionAction();}
        }catch(Throwable t){Log.e(TAG,"Import batch failed",t);errors.add(rootMessage(t));}
        String message=success>0?"已导入并启用 "+success+" 个 Lua 脚本":"没有导入脚本";if(!errors.isEmpty())message+="\n失败 "+errors.size()+" 个："+errors.get(0);Toast.makeText(activity,message,Toast.LENGTH_LONG).show();notifyUiChanged();}

    public void setUiListener(Runnable listener){uiChanged=listener;}
    public void clearUiListener(Runnable listener){if(uiChanged==listener)uiChanged=null;}
    public void refreshForUi(){new Thread(()->{try{if(ensureStorage()){if(!loaded)reload();else maybeReload();}}catch(Throwable t){Log.e(TAG,"Unable to refresh script list",t);}notifyUiChanged();},"RWmiao-script-list").start();}

    public boolean hasEnabledScripts(){synchronized(lock){for(RuntimeScript r:scripts.values())if(isEnabled(r.definition().id))return true;}return false;}
    public boolean hasApplicableUnit(Object raw){if(!masterEnabled())return false;synchronized(lock){boolean any=false;for(RuntimeScript r:scripts.values())if(isEnabled(r.definition().id)){any=true;break;}if(!any)return false;}if(!adapter.isOwnedOrderable(raw))return false;synchronized(lock){for(RuntimeScript r:scripts.values())if(isEnabled(r.definition().id)&&adapter.matchesType(raw,r.definition()))return true;}return false;}
    public void openUnitPanel(Activity activity, Object rawUnit) {
        if (activity == null || activity.isFinishing()) return;
        try {
            UnitSnapshot unit = adapter.snapshotOne(rawUnit);
            if (unit == null || unit.relation != 0 || !unit.orderable || unit.dead || unit.deleted) {
                Toast.makeText(activity, "该单位不可使用脚本", Toast.LENGTH_LONG).show();
                return;
            }
            ArrayList<RuntimeScript> applicable = new ArrayList<>();
            synchronized (lock) {
                for (RuntimeScript runtime : scripts.values()) {
                    ScriptDefinition definition = runtime.definition();
                    if (isEnabled(definition.id)
                            && definition.acceptsUnit(unit.typeId, unit.typeName)) {
                        applicable.add(runtime);
                    }
                }
            }
            if (applicable.isEmpty()) {
                Toast.makeText(activity, "此单位没有可使用的脚本", Toast.LENGTH_LONG).show();
                return;
            }
            List<UnitSnapshot> selected=adapter.selectedOwnedSameType(rawUnit);
            ArrayList<Long> selectedIds=new ArrayList<>();for(UnitSnapshot selectedUnit:selected)selectedIds.add(selectedUnit.id);
            if(selectedIds.isEmpty())selectedIds.add(unit.id);
            String[] labels = new String[applicable.size()];
            boolean[] pending = new boolean[applicable.size()];
            for (int i = 0; i < applicable.size(); i++) {
                ScriptDefinition definition = applicable.get(i).definition();
                int enabledCount=0;for(Long id:selectedIds)if(unitEnabled(definition,id))enabledCount++;
                labels[i] = definition.name+(enabledCount>0&&enabledCount<selectedIds.size()?"（部分已启用）":"");
                pending[i]=enabledCount>0;
            }
            RuntimePanels.showScriptManager(
                    activity,
                    "脚本管理",
                    labels,
                    pending,
                    values -> {
                        synchronized (lock) {
                            String group="selection-"+(++nextGroupId);
                            for (int i = 0; i < applicable.size(); i++) {
                                ScriptDefinition definition = applicable.get(i).definition();
                                LinkedHashSet<Long> bindings=unitBindings.computeIfAbsent(definition.id, ignored -> new LinkedHashSet<>());
                                Map<Long,String> groups=unitGroups.computeIfAbsent(definition.id,ignored->new HashMap<>());
                                if(values[i]){bindings.addAll(selectedIds);for(Long id:selectedIds)groups.put(id,group);}else{bindings.removeAll(selectedIds);for(Long id:selectedIds)groups.remove(id);if(bindings.isEmpty())unitBindings.remove(definition.id);if(groups.isEmpty())unitGroups.remove(definition.id);}
                            }
                        }
                        refreshSettings();
                    });
        } catch (Throwable t) {
            Log.e(TAG, "Unable to open unit script panel", t);
            Toast.makeText(activity, "无法打开脚本管理：" + rootMessage(t), Toast.LENGTH_LONG).show();
        }
    }
    private boolean unitEnabled(ScriptDefinition d,long id){synchronized(lock){LinkedHashSet<Long> ids=unitBindings.get(d.id);return ids!=null&&ids.contains(id);}}
    private boolean hasEnabledUnits(ScriptDefinition d){synchronized(lock){LinkedHashSet<Long> ids=unitBindings.get(d.id);return ids!=null&&!ids.isEmpty();}}
    private boolean hasRunnableBindings(){synchronized(lock){for(RuntimeScript r:scripts.values())if(!r.runtimeDisabled&&isEnabled(r.definition().id)&&hasEnabledUnits(r.definition()))return true;}return false;}
    private String unitGroup(ScriptDefinition d,long id){synchronized(lock){Map<Long,String> groups=unitGroups.get(d.id);return groups==null?null:groups.get(id);}}
    private void finishUnit(ScriptDefinition d,long id){synchronized(lock){LinkedHashSet<Long> ids=unitBindings.get(d.id);if(ids!=null&&ids.remove(id)){hookStateDirty=true;if(ids.isEmpty())unitBindings.remove(d.id);}Map<Long,String> groups=unitGroups.get(d.id);if(groups!=null){groups.remove(id);if(groups.isEmpty())unitGroups.remove(d.id);}}}
    private void exitScript(ScriptDefinition d,String message){synchronized(lock){if(unitBindings.remove(d.id)!=null)hookStateDirty=true;unitGroups.remove(d.id);}if(message!=null&&!message.trim().isEmpty())adapter.localMessage("["+d.name+"] "+message.trim());}
    private void clearMatchState(){synchronized(lock){unitBindings.clear();unitGroups.clear();adapter.clearTransientState();commands.clearTransientState();for(RuntimeScript r:scripts.values()){try{r.program=r.program.resetForNewMatch();}catch(Throwable t){Log.e(TAG,"Unable to reset Lua VM for new match: "+r.definition().id,t);r.program.resetTickState();}r.runtimeDisabled=false;}}refreshSettings();}
    private void prepareAfterResync(){synchronized(lock){adapter.clearTransientState();commands.clearTransientState();for(RuntimeScript r:scripts.values())r.program.resetTickState();}}
    private boolean ensureStorage()throws Throwable{context=host.preferenceContext();if(context==null)return false;directory=new File(context.getFilesDir(),"rwmiao/scripts");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("无法创建脚本目录");File marker=new File(directory,".defaults_install_stamp");android.content.pm.ApplicationInfo moduleInfo=host.getModuleApplicationInfo();File moduleApk=moduleInfo==null||moduleInfo.sourceDir==null?null:new File(moduleInfo.sourceDir);String installStamp=moduleApk==null?"unknown":moduleApk.getAbsolutePath()+":"+moduleApk.lastModified()+":"+moduleApk.length();
        String previous=marker.isFile()?read(new FileInputStream(marker)):null;
        if(!installStamp.equals(previous)){
            seedPackagedScripts();
            write(marker,installStamp);
        }
        return true;}

    private void seedPackagedScripts() throws IOException {
        android.content.pm.ApplicationInfo info=host.getModuleApplicationInfo();
        if(info==null||info.sourceDir==null)return;
        try(ZipFile apk=new ZipFile(info.sourceDir)){
            Enumeration<? extends ZipEntry> entries=apk.entries();
            while(entries.hasMoreElements()){
                ZipEntry entry=entries.nextElement();String name=entry.getName();
                if(entry.isDirectory()||!name.startsWith("Config/Scripts/")||!name.toLowerCase().endsWith(".lua"))continue;
                String fileName=new File(name).getName();if(fileName.isEmpty())continue;
                File target=new File(directory,fileName);if(target.exists())continue;
                try(InputStream input=apk.getInputStream(entry)){String source=read(input);LuaProgram.compile(source,fileName);write(target,source);}
                catch(Throwable t){Log.e(TAG,"Packaged default script rejected: "+name,t);}
            }
        }
    }

    public void reload()throws IOException{
        ArrayList<File> files=new ArrayList<>();File[] listed=directory.listFiles((d,n)->n.toLowerCase().endsWith(".lua"));if(listed!=null)Collections.addAll(files,listed);files.sort(Comparator.comparing(File::getName));
        LinkedHashMap<String,RuntimeScript> next=new LinkedHashMap<>();long stamp=0;
        for(File file:files){stamp=Math.max(stamp,file.lastModified());try{LuaProgram program=LuaProgram.compile(read(new FileInputStream(file)),file.getName());if(next.containsKey(program.definition.id))throw new IOException("重复脚本 id: "+program.definition.id);next.put(program.definition.id,new RuntimeScript(program));}
            catch(Throwable t){Log.e(TAG,"Script rejected, retaining previous VM when possible: "+file.getName(),t);synchronized(lock){for(RuntimeScript old:scripts.values())if(old.definition().sourceName.equals(file.getName()))next.put(old.definition().id,old);}}}
        synchronized(lock){scripts.clear();scripts.putAll(next);settingCache.clear();if(unitBindings.keySet().retainAll(next.keySet()))hookStateDirty=true;unitGroups.keySet().retainAll(next.keySet());}directoryStamp=Math.max(stamp,directory.lastModified());loaded=true;
    }
    private void maybeReload(){long stamp=directory.lastModified();File[] files=directory.listFiles((d,n)->n.toLowerCase().endsWith(".lua"));if(files!=null)for(File f:files)stamp=Math.max(stamp,f.lastModified());if(stamp!=directoryStamp)try{reload();}catch(Throwable t){Log.e(TAG,"Hot reload failed",t);}}
    private SharedPreferences preferences(){return context.getSharedPreferences(PREFS_NAME,Context.MODE_PRIVATE);}
    private void notifyUiChanged(){Runnable r=uiChanged;if(r!=null)r.run();}
    private static String read(InputStream input)throws IOException{try(InputStream source=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n,total=0;while((n=source.read(b))!=-1){total+=n;if(total>MAX_SOURCE_BYTES)throw new IOException("脚本超过 256KB");out.write(b,0,n);}return out.toString(StandardCharsets.UTF_8.name());}}
    private static void write(File file,String source)throws IOException{try(FileOutputStream out=new FileOutputStream(file)){out.write(source.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}}
    private static String queryName(Activity a,Uri uri){android.database.Cursor c=null;try{c=a.getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null);return c!=null&&c.moveToFirst()?c.getString(0):null;}catch(Throwable ignored){return null;}finally{if(c!=null)c.close();}}
    private static String rootMessage(Throwable t){while(t.getCause()!=null&&t.getCause()!=t)t=t.getCause();return t.getMessage()==null?t.getClass().getSimpleName():t.getMessage();}
    private static int dp(Context c,int v){return(int)(v*c.getResources().getDisplayMetrics().density+0.5f);}
    public static final class ImportFragment extends Fragment{static final int REQUEST=0x5257;ScriptManager manager;@Override public void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode!=REQUEST||resultCode!=Activity.RESULT_OK||data==null||manager==null)return;ArrayList<Uri> uris=new ArrayList<>();ClipData clip=data.getClipData();if(clip!=null)for(int i=0;i<clip.getItemCount();i++){Uri uri=clip.getItemAt(i).getUri();if(uri!=null&&!uris.contains(uri))uris.add(uri);}else if(data.getData()!=null)uris.add(data.getData());manager.importUris(getActivity(),uris);}}
}



