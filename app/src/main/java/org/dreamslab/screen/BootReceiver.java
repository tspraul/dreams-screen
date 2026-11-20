package org.dreamslab.screen;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Opens the screen again after the panel is switched on or the app is updated.
 *  Some Android versions block this; then open the app once from the app list. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            Intent i = new Intent(context, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Exception ignored) { }
    }
}
