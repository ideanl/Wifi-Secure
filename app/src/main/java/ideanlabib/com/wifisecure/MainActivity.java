package ideanlabib.com.wifisecure;

import android.app.ProgressDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v7.app.AppCompatActivity;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import ideanlabib.com.wifisecure.openvpn.OpenVPNService;
import ideanlabib.com.wifisecure.openvpn.ProfileManager;
import ideanlabib.com.wifisecure.openvpn.VPNLaunchHelper;
import ideanlabib.com.wifisecure.openvpn.VpnProfile;
import ideanlabib.com.wifisecure.openvpn.VpnStatus;

public class MainActivity extends AppCompatActivity {
    private Button secureButton;
    ProgressDialog progress;

    protected OpenVPNService mService;

    private VpnProfile profile;

    private final int   LISTEN_PORT = 11445,
                        TUNNEL_PORT = 443;
    private final String TUNNEL_HOST = "ideanvpn.tk";

    private int START_VPN_DIALOG = 1;

    private Connector server;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        secureButton = (Button) findViewById(R.id.secure);
        progress = new ProgressDialog(this);

        profile = getProfileManager().getProfileByName("IdeanVPN");
        if (profile == null)
            createProfile();
        else
            secureButton.setEnabled(true);
    }

    public void secure(View v) {
        secure();
    }

    public void secure() {
        secureButton.setEnabled(false);
        /*server = new Connector(LISTEN_PORT, TUNNEL_HOST,
                TUNNEL_PORT, this);
        server.start();*/
        startVPN();
    }

    public void unsecure() {
        secureButton.setEnabled(false);
        if (server != null) {
            try {
                //close the server socket and interrupt the server thread
                //server.ss.close();
                //server.interrupt();
                stopVPN();
            } catch (Exception e) {
                Log.d("Connector", "Interrupt failure: " + e.toString());
            }
        }
        Log.d("Connector", "Stopping tunnel " + LISTEN_PORT + ":" + TUNNEL_HOST + ":" + TUNNEL_PORT);

        update(false);
    }

    public void update(boolean secured) {
        Button button = ((Button) findViewById(R.id.secure));
        if (secured) {
            button.setText("Unsecure Me");

            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    unsecure();
                }
            });
        } else {
            button.setText("Secure Me!");

            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    secure();
                }
            });
        }

        button.setEnabled(true);
    }


    private void startVPN() {
        Intent intent = VpnService.prepare(this);
        if (intent != null)
            startActivityForResult(intent, START_VPN_DIALOG);
        else
            onActivityResult(START_VPN_DIALOG, RESULT_OK, intent);

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (VpnStatus.mLaststate != "CONNECTED") {
                        Thread.sleep(500);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }

                update(true);
            }
        });
    }

    private void stopVPN() {
        try {
            mService.getManagement().stopVPN();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == START_VPN_DIALOG) {
            if (resultCode == RESULT_OK) {
                try {
                    FileWriter cfg = new FileWriter(VPNLaunchHelper.getConfigFilePath(this));
                    cfg.write(profile.getConfigFile(this, false));
                    cfg.flush();
                    cfg.close();
                } catch (IOException e) {
                    VpnStatus.logException(e);
                }
                Log.d("Connector", "SUCCESS!");
                Intent intent = new Intent(this, OpenVPNService.class);
                bindService(intent, connection, Context.BIND_AUTO_CREATE);
                startService(intent);
            } else {
                Log.d("Connector", "FAILURE!");
            }
        }
    }

    public void createProfile() {
        progress.setTitle("Initializing");
        progress.setMessage("Please wait...");
        progress.show();

        final Context context = this;

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.d("Connector", "CREATING PROFILE....");
                    //String client = getPhoneNumber();
                    String client = "idean";
                    if (!(new File(getFilesDir().getAbsolutePath() + "/ca.crt").exists())) {
                        downloadFile(context, "http://ideanlabib.tk/files/openvpn/ca.crt", "ca.crt");
                    }
                    String caFileContents = readFile("ca.crt");

                    String keyname = client + ".key";
                    if (!(new File(getFilesDir().getAbsolutePath() + "/" + keyname).exists())) {
                        downloadFile(context, "http://ideanlabib.tk/files/openvpn/" + keyname, keyname);
                    }
                    String clientKeyFileContents = readFile(keyname);

                    String certname = client + ".crt";
                    if (!(new File(getFilesDir().getAbsolutePath() + "/" + certname).exists())) {
                        downloadFile(context, "http://ideanlabib.tk/files/openvpn/" + certname, certname);
                    }
                    String clientCertFileContents = readFile(certname);

                    ProfileManager pm = getProfileManager();
                    profile = new VpnProfile("IdeanVPN");
                    profile.mUseLzo = true;
                    //Certificate stuff
                    //profile.mServerName = "67.183.127.61";
                    profile.mServerName = "127.0.0.1";
                    profile.mServerPort = "1144";

                    profile.mUseUdp = false;
                    profile.mConnectRetryMax = "5";
                    profile.mConnectRetry = "5";
                    profile.mUseDefaultRoute = true;
                    profile.mUseDefaultRoutev6 = true;
                    profile.mUsePull = true;
                    profile.mPersistTun = true;

                    profile.mConnections[0].mEnabled = true;
                    profile.mConnections[0].mServerName = "67.183.127.61";
                    profile.mConnections[0].mServerPort = "1144";
                    profile.mConnections[0].mUseUdp = false;

                    profile.mCaFilename = "[[NAME]]ca.crt[[INLINE]]" + caFileContents;
                    profile.mClientKeyFilename = "[[NAME]]" + client + ".key[[INLINE]]" + clientKeyFileContents;
                    profile.mClientCertFilename = "[[NAME]]" + client + ".cert[[INLINE]]" + clientCertFileContents;
                    pm.addProfile(profile);
                    pm.saveProfileList(context);
                    pm.saveProfile(context, profile);

                    Handler handler = new Handler(Looper.getMainLooper());
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            secureButton.setEnabled(true);
                            progress.dismiss();
                        }
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        thread.start();
    }

    private ProfileManager getProfileManager() {
        return ProfileManager.getInstance(this);
    }

    private String getPhoneNumber() {
        TelephonyManager tMgr = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
        return tMgr.getLine1Number();
    }

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            OpenVPNService.LocalBinder binder = (OpenVPNService.LocalBinder) service;
            mService = binder.getService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d("Connector", "DISCONNECTED!");
        }
    };

    private String readFile(String name) {
        try {
            FileInputStream fis = openFileInput(name);
            StringBuilder out = new StringBuilder();
            byte[] buffer = new byte[10240];
            try {
                for (int length = 0; (length = fis.read(buffer)) > 0;) {
                    out.append(new String(buffer, 0, length));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            fis.close();

            return out.toString();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private void downloadFile(final Context context, final String url, final String filename) {
        try {
            URL website = new URL(url);
            HttpURLConnection connection = (HttpURLConnection) website.openConnection();
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.d("Connector", "Server returned HTTP " + connection.getResponseCode()
                        + " " + connection.getResponseMessage());
            }

            InputStream input = connection.getInputStream();
            FileOutputStream output = context.openFileOutput(filename, Context.MODE_PRIVATE);

            byte data[] = new byte[4096];
            int count;
            while ((count = input.read(data)) != -1) {
                output.write(data, 0, count);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}