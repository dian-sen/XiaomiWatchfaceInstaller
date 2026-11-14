package dev.xiaom.watchfaceinstaller;

import android.os.ParcelFileDescriptor;

interface ICacheReader {
    String scan() = 0;
    ParcelFileDescriptor openReadOnly(String path) = 1;
    byte[] readMetadata(String binPath, String relative) = 2;
    // Shizuku sends transaction 16777115 (AIDL adds FIRST_CALL_TRANSACTION).
    void destroy() = 16777114;
}
