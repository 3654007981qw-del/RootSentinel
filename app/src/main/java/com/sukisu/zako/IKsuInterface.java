/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: /home/hatch/android-sdk/build-tools/35.0.1/aidl -I/home/hatch/workspace/upstream/SukiSU-Nova/manager/app/src/main/aidl -I/tmp/pls/aidl -I/tmp/aidl-stub -o/tmp/aidl-out /home/hatch/workspace/upstream/SukiSU-Nova/manager/app/src/main/aidl/com/sukisu/zako/IKsuInterface.aidl
 */
package com.sukisu.zako;
public interface IKsuInterface extends android.os.IInterface
{
  /** Default implementation for IKsuInterface. */
  public static class Default implements com.sukisu.zako.IKsuInterface
  {
    @Override public rikka.parcelablelist.ParcelableListSlice<android.content.pm.PackageInfo> getPackages(int flags) throws android.os.RemoteException
    {
      return null;
    }
    @Override public int[] getUserIds() throws android.os.RemoteException
    {
      return null;
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements com.sukisu.zako.IKsuInterface
  {
    /** Construct the stub at attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an com.sukisu.zako.IKsuInterface interface,
     * generating a proxy if needed.
     */
    public static com.sukisu.zako.IKsuInterface asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof com.sukisu.zako.IKsuInterface))) {
        return ((com.sukisu.zako.IKsuInterface)iin);
      }
      return new com.sukisu.zako.IKsuInterface.Stub.Proxy(obj);
    }
    @Override public android.os.IBinder asBinder()
    {
      return this;
    }
    @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
    {
      java.lang.String descriptor = DESCRIPTOR;
      if (code >= android.os.IBinder.FIRST_CALL_TRANSACTION && code <= android.os.IBinder.LAST_CALL_TRANSACTION) {
        data.enforceInterface(descriptor);
      }
      if (code == INTERFACE_TRANSACTION) {
        reply.writeString(descriptor);
        return true;
      }
      switch (code)
      {
        case TRANSACTION_getPackages:
        {
          int _arg0;
          _arg0 = data.readInt();
          rikka.parcelablelist.ParcelableListSlice<android.content.pm.PackageInfo> _result = this.getPackages(_arg0);
          reply.writeNoException();
          _Parcel.writeTypedObject(reply, _result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_getUserIds:
        {
          int[] _result = this.getUserIds();
          reply.writeNoException();
          reply.writeIntArray(_result);
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static class Proxy implements com.sukisu.zako.IKsuInterface
    {
      private android.os.IBinder mRemote;
      Proxy(android.os.IBinder remote)
      {
        mRemote = remote;
      }
      @Override public android.os.IBinder asBinder()
      {
        return mRemote;
      }
      public java.lang.String getInterfaceDescriptor()
      {
        return DESCRIPTOR;
      }
      @Override public rikka.parcelablelist.ParcelableListSlice<android.content.pm.PackageInfo> getPackages(int flags) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        rikka.parcelablelist.ParcelableListSlice<android.content.pm.PackageInfo> _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(flags);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getPackages, _data, _reply, 0);
          _reply.readException();
          _result = _Parcel.readTypedObject(_reply, rikka.parcelablelist.ParcelableListSlice.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public int[] getUserIds() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int[] _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getUserIds, _data, _reply, 0);
          _reply.readException();
          _result = _reply.createIntArray();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
    }
    static final int TRANSACTION_getPackages = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_getUserIds = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "com.sukisu.zako.IKsuInterface";
  public rikka.parcelablelist.ParcelableListSlice<android.content.pm.PackageInfo> getPackages(int flags) throws android.os.RemoteException;
  public int[] getUserIds() throws android.os.RemoteException;
  /** @hide */
  static class _Parcel {
    static private <T> T readTypedObject(
        android.os.Parcel parcel,
        android.os.Parcelable.Creator<T> c) {
      if (parcel.readInt() != 0) {
          return c.createFromParcel(parcel);
      } else {
          return null;
      }
    }
    static private <T extends android.os.Parcelable> void writeTypedObject(
        android.os.Parcel parcel, T value, int parcelableFlags) {
      if (value != null) {
        parcel.writeInt(1);
        value.writeToParcel(parcel, parcelableFlags);
      } else {
        parcel.writeInt(0);
      }
    }
  }
}
