package io.github.ekkzy.evebox.data;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.UUID;
import io.github.ekkzy.evebox.BuildConfig;

/** Diagnostic counts only; never serializes program text, provider row IDs, URIs or extras. */
public final class ChannelProbeReport {
    public static final String TAG="SL_CHANNEL_PROBE";
    private ChannelProbeReport() { }
    /** Call from the probe worker, only in a build with the internal probe enabled. */
    public static void log(Context context,ChannelProbeSession.Result result){
        if(!BuildConfig.CHANNEL_PROBE_ENABLED)return;
        String runId=UUID.randomUUID().toString();
        try {
            PackageInfo app=context.getPackageManager().getPackageInfo(context.getPackageName(),0);
            JSONObject header=record(runId,"context").put("packageName",context.getPackageName())
                    .put("uid",Process.myUid()).put("versionCode",version(app)).put("api",Build.VERSION.SDK_INT).put("debugBuild",BuildConfig.DEBUG)
                    .put("permissionGranted",WatchNextRepository.hasReadPermission(context)).put("dataOnly",true)
                    .put("outcome",result.outcome.name()).put("sourcePackage",result.sourcePackage).put("elapsedMillis",result.elapsedMillis);
            if(result.discovery!=null)header.put("status",result.discovery.status.name()).put("completeness",result.discovery.completeness.name())
                    .put("systemBrowsableChannels",result.discovery.channels.size()).put("selectableChannels",result.discovery.selectableChannels.size()).put("stats",stats(result.discovery.stats));
            Log.i(TAG,header.toString());
            if(result.discovery!=null)for(ChannelRepository.SourceSummary source:result.discovery.sources)
                Log.i(TAG,record(runId,"source").put("packageName",source.packageName).put("versionCode",source.versionCode)
                        .put("ownerAvailable",source.ownerAvailable).put("scannedChannels",source.scannedChannelCount)
                        .put("eligibleChannels",source.eligibleChannelCount).put("hiddenChannels",source.hiddenChannelCount)
                        .put("selectableChannels",source.selectableChannelCount).put("browsableOffChannels",source.browsableOffChannelCount)
                        .put("searchableOffChannels",source.searchableOffChannelCount).put("lockedChannels",source.lockedChannelCount)
                        .put("rejectedChannels",source.rejectedChannelCount).toString());
            for(int i=0;i<result.programs.size();i++){
                ChannelRepository.Programs programs=result.programs.get(i);
                Log.i(TAG,record(runId,"programScan").put("ordinal",i)
                        .put("packageName",programs.channel==null?"":programs.channel.packageName)
                        .put("status",programs.status.name()).put("completeness",programs.completeness.name())
                        .put("stats",stats(programs.stats)).toString());
            }
        }catch(android.content.pm.PackageManager.NameNotFoundException|JSONException|RuntimeException unavailable){
            Log.i(TAG,"{\"kind\":\"reportUnavailable\",\"dataOnly\":true}");
        }
    }
    private static long version(PackageInfo info){return Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;}
    private static JSONObject record(String runId,String kind)throws JSONException{return new JSONObject().put("runId",runId).put("kind",kind);}
    private static JSONObject stats(ChannelRepository.Stats s)throws JSONException{
        return new JSONObject().put("scanned",s.scanned).put("eligible",s.eligible).put("retained",s.retained)
                .put("rejected",s.rejected).put("hidden",s.hidden).put("unknownVisibility",s.unknownVisibility)
                .put("ownerUnavailable",s.ownerUnavailable).put("locked",s.locked).put("duplicateConflicts",s.duplicateConflicts)
                .put("invalidIntents",s.invalidIntents).put("appOnlyIntents",s.appOnlyIntents)
                .put("imageUriPresent",s.imageUriPresent).put("displayLimited",s.displayLimited).put("elapsedMillis",s.elapsedMillis);
    }
}
