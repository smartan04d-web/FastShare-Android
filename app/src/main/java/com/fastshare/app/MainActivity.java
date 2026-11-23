package com.fastshare.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.content.Context;
import android.view.WindowManager;
import android.widget.*;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends androidx.activity.ComponentActivity {
    private static final int PORT=43881;
    private static final String MAGIC="FASTSHARE1";
    private static final long MAX_BYTES=500L*1024*1024*1024;
    private final ExecutorService executor=Executors.newCachedThreadPool();
    private volatile ServerSocket server;
    private Uri selected;
    private String peerHost;
    private TextView status,address;
    private EditText ip;
    private ImageView qr;
    private ProgressBar progress;
    private final ActivityResultLauncher<String[]> pickFile=registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri->{
        if(uri!=null){ selected=uri; show("Selected: "+fileName(uri)+". Scan QR or enter receiver IP, then tap Send."); }
    });
    private final ActivityResultLauncher<ScanOptions> scan=registerForActivityResult(new ScanContract(), result->{
        if(result.getContents()==null){show("QR scan cancelled");return;}
        try{
            Uri u=Uri.parse(result.getContents());
            if(!"fastshare".equals(u.getScheme()) || !"pair".equals(u.getHost())) throw new Exception("Not a FastShare pairing QR");
            String host=u.getQueryParameter("host");
            if(host==null || !validIpv4(host)) throw new Exception("Invalid receiver IP");
            peerHost=host; ip.setText(host);show("Paired with "+host+". Select a file and tap Send.");
        }catch(Exception e){show("QR error: "+e.getMessage());}
    });
    @Override public void onCreate(Bundle state){super.onCreate(state);setContentView(com.fastshare.app.R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        status=findViewById(R.id.status);address=findViewById(R.id.address);ip=findViewById(R.id.ip);
        qr=findViewById(R.id.qr);progress=findViewById(R.id.progress);
        findViewById(R.id.wifi).setOnClickListener(v->startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)));
        findViewById(R.id.receive).setOnClickListener(v->startReceiver());
        findViewById(R.id.pick).setOnClickListener(v->pickFile.launch(new String[]{"*/*"}));
        findViewById(R.id.scan).setOnClickListener(v->scan.launch(new ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan FastShare receiver QR").setBeepEnabled(false).setOrientationLocked(false)));
        findViewById(R.id.send).setOnClickListener(v->startSender());
    }
    private void show(String s){runOnUiThread(()->status.setText(s));}
    private void update(long done,long total,long start,String prefix){
        long elapsed=Math.max(1,System.currentTimeMillis()-start);
        double mbps=done*1000.0/elapsed/1048576.0;
        int pct=total>0?(int)Math.min(100,done*100/total):100;
        runOnUiThread(()->{progress.setProgress(pct);status.setText(String.format(Locale.US,"%s %d%% • %.1f / %.1f MB • %.2f MB/s",prefix,pct,done/1048576.0,total/1048576.0,mbps));});
    }
    private void startReceiver(){
        if(server!=null && !server.isClosed()){show("Receiver already running");return;}
        String host=hotspotAddress();
        address.setText(host==null?"Cannot detect hotspot IP. Enable hotspot, then restart receiver.":"Receiver: "+host+":"+PORT+" • Connect sender to this hotspot");
        if(host!=null){try{
            String payload="fastshare://pair?host="+host;
            BitMatrix m=new MultiFormatWriter().encode(payload,BarcodeFormat.QR_CODE,600,600);
            Bitmap b=Bitmap.createBitmap(600,600,Bitmap.Config.ARGB_8888);
            for(int y=0;y<600;y++)for(int x=0;x<600;x++)b.setPixel(x,y,m.get(x,y)?0xff111111:0xffffffff);
            qr.setImageBitmap(b);qr.setVisibility(android.view.View.VISIBLE);
        }catch(Exception e){show("QR creation failed: "+e.getMessage());}}
        executor.execute(()->{
            try(ServerSocket ss=new ServerSocket()){server=ss;ss.setReuseAddress(true);ss.bind(new InetSocketAddress(PORT));
                show("Receiver listening on port "+PORT+". Keep app open.");
                while(!ss.isClosed()){
                    Socket client=ss.accept();
                    try{receiveOne(client);}catch(Exception e){show("Receive error: "+e.getMessage());}
                }
            }catch(Exception e){if(!isFinishing())show("Server error: "+e.getMessage());}finally{server=null;}
        });
    }
    private void receiveOne(Socket socket)throws Exception{
        try(Socket s=socket){s.setSoTimeout(120000);
            DataInputStream in=new DataInputStream(new BufferedInputStream(s.getInputStream()));
            DataOutputStream out=new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            if(!MAGIC.equals(in.readUTF()))throw new IOException("Unknown protocol");
            String name=in.readUTF();long length=in.readLong();
            if(length<0 || length>MAX_BYTES)throw new IOException("Invalid file size");
            name=safeName(name);
            // SAF CreateDocument prompts user to choose a save location; this MVP uses MediaStore Downloads instead.
            android.content.ContentValues values=new android.content.ContentValues();
            values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME,name);
            values.put(android.provider.MediaStore.Downloads.MIME_TYPE,"application/octet-stream");
            values.put(android.provider.MediaStore.Downloads.IS_PENDING,1);
            Uri target=getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
            if(target==null)throw new IOException("Cannot create download");
            boolean good=false;
            try(OutputStream file=getContentResolver().openOutputStream(target)){
                if(file==null)throw new IOException("Cannot open output");
                MessageDigest digest=MessageDigest.getInstance("SHA-256");
                byte[] buffer=new byte[128*1024];long done=0,start=System.currentTimeMillis();
                while(done<length){int n=in.read(buffer,0,(int)Math.min(buffer.length,length-done));if(n<0)throw new EOFException("Sender disconnected");file.write(buffer,0,n);digest.update(buffer,0,n);done+=n;update(done,length,start,"Receiving");}
                file.flush();byte[] expected=new byte[32];in.readFully(expected);
                good=MessageDigest.isEqual(expected,digest.digest());
            }finally{
                if(good){android.content.ContentValues finish=new android.content.ContentValues();finish.put(android.provider.MediaStore.Downloads.IS_PENDING,0);getContentResolver().update(target,finish,null,null);}
                else getContentResolver().delete(target,null,null);
            }
            out.writeBoolean(good);out.flush();
            show(good?"Saved successfully in Downloads: "+name:"Checksum mismatch; partial file removed");
        }
    }
    private void startSender(){
        if(selected==null){show("Choose a file first");return;}
        String host=ip.getText().toString().trim();if(host.isEmpty())host=peerHost;
        if(host==null || !validIpv4(host)){show("Scan QR or enter valid receiver IPv4 address");return;}
        final String dest=host;final Uri file=selected;
        executor.execute(()->{
            try(Socket socket=new Socket()){socket.connect(new InetSocketAddress(dest,PORT),10000);socket.setSoTimeout(120000);
                DataOutputStream out=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                DataInputStream in=new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                long size=fileSize(file);if(size<0 || size>MAX_BYTES)throw new IOException("Cannot determine file size or file exceeds limit");
                out.writeUTF(MAGIC);out.writeUTF(safeName(fileName(file)));out.writeLong(size);
                MessageDigest digest=MessageDigest.getInstance("SHA-256");
                try(InputStream source=getContentResolver().openInputStream(file)){
                    if(source==null)throw new IOException("Cannot open selected file");
                    byte[] buffer=new byte[128*1024];long done=0,start=System.currentTimeMillis();
                    while(done<size){int n=source.read(buffer,0,(int)Math.min(buffer.length,size-done));if(n<0)throw new EOFException("File changed while sending");out.write(buffer,0,n);digest.update(buffer,0,n);done+=n;update(done,size,start,"Sending");}
                }
                out.write(digest.digest());out.flush();
                show(in.readBoolean()?"Transfer complete and checksum verified":"Receiver reported checksum failure");
            }catch(Exception e){show("Send failed: "+e.getMessage());}
        });
    }
    private String fileName(Uri u){try(Cursor c=getContentResolver().query(u,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
        if(c!=null&&c.moveToFirst())return c.getString(0);
    }catch(Exception ignored){}return "shared_file";}
    private long fileSize(Uri u){try(Cursor c=getContentResolver().query(u,new String[]{OpenableColumns.SIZE},null,null,null)){
        if(c!=null&&c.moveToFirst()&&!c.isNull(0))return c.getLong(0);
    }catch(Exception ignored){}return -1;}
    private static String safeName(String n){n=n.replace('\\','/');n=n.substring(n.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]","_").trim();return n.isEmpty()?"shared_file":n;}
    private static boolean validIpv4(String ip){try{String[] a=ip.split("\\.",-1);if(a.length!=4)return false;for(String part:a){if(part.isEmpty()||part.length()>3)return false;int x=Integer.parseInt(part);if(x<0||x>255)return false;}return true;}catch(Exception e){return false;}}
    private static String hotspotAddress(){try{
        Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
        String fallback=null;
        while(interfaces.hasMoreElements()){
            NetworkInterface ni=interfaces.nextElement();if(!ni.isUp()||ni.isLoopback())continue;
            Enumeration<InetAddress> ips=ni.getInetAddresses();
            while(ips.hasMoreElements()){
                InetAddress a=ips.nextElement();if(!(a instanceof Inet4Address)||a.isLoopbackAddress()||!a.isSiteLocalAddress())continue;
                String addr=a.getHostAddress();String iface=ni.getName().toLowerCase(Locale.US);
                if(iface.contains("ap")||iface.contains("swlan")||iface.contains("wlan")||iface.contains("wifi"))return addr;
                if(fallback==null)fallback=addr;
            }
        }return fallback;
    }catch(Exception e){return null;}}
    @Override protected void onDestroy(){super.onDestroy();try{if(server!=null)server.close();}catch(Exception ignored){}executor.shutdownNow();}
}
