package org.zxor.oronbox;

import android.os.ParcelFileDescriptor;

/** Only typed, whitelisted cache reads cross this boundary. No shell programs or output paths. */
interface IWatchfaceCacheShizukuService {
    ParcelFileDescriptor read(String kind, String bin, String relative, long budgetMillis) = 0;
    int uid() = 1;
    // Shizuku reserves transaction 16777115 for terminating a non-daemon UserService.
    void destroy() = 16777114;
}
