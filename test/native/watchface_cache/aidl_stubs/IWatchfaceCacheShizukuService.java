package org.zxor.oronbox;
import android.os.*;
// Temporary signature stub only; real implementations are generated from the checked-in AIDL.
public interface IWatchfaceCacheShizukuService extends IInterface {
 ParcelFileDescriptor read(String kind, String bin, String relative, long budgetMillis) throws RemoteException;
 int uid() throws RemoteException;
 void destroy() throws RemoteException;
 abstract class Stub extends Binder implements IWatchfaceCacheShizukuService {
  public IBinder asBinder() { return this; }
  public static IWatchfaceCacheShizukuService asInterface(IBinder binder) { return null; }
 }
}
