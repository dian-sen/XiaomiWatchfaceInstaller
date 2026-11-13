package dev.xiaom.watchfaceinstaller;

import android.os.ParcelFileDescriptor;

interface ICacheReader {
    String scan();
    ParcelFileDescriptor openReadOnly(String path);
    void destroy();
}
