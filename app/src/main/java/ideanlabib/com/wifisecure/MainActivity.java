package ideanlabib.com.wifisecure;

import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import ideanlabib.com.wifisecure.openvpn.OpenVPNService;
import ideanlabib.com.wifisecure.openvpn.ProfileManager;
import ideanlabib.com.wifisecure.openvpn.VpnProfile;

public class MainActivity extends AppCompatActivity {
    private Button secureButton;

    private final int   LISTEN_PORT = 11445,
                        TUNNEL_PORT = 443;
    private final String TUNNEL_HOST = "ideanvpn.tk";

    private int START_VPN_DIALOG = 1;

    private Connector server;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (getProfileManager().getProfileByName("IdeanVPN") == null)
            createProfile();

        secureButton = (Button) findViewById(R.id.secure);
    }

    public void secure(View v) {
        secure();
    }

    public void secure() {
        secureButton.setEnabled(false);
        server = new Connector(LISTEN_PORT, TUNNEL_HOST,
                TUNNEL_PORT, this);
        server.start();
        startVPN();
    }

    public void unsecure() {
        secureButton.setEnabled(false);
        if (server != null) {
            try {
                //close the server socket and interrupt the server thread
                server.ss.close();
                server.interrupt();
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
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == START_VPN_DIALOG) {
            if (resultCode == RESULT_OK) {
                Log.d("Connector", "SUCCESS!");
                startService(new Intent(this, OpenVPNService.class));
            } else {
                Log.d("Connector", "FAILURE!");
            }
        }
    }

    public void createProfile() {
        ProfileManager pm = getProfileManager();
        VpnProfile profile = new VpnProfile("IdeanVPN");
        profile.mUseLzo = true;

        pm.addProfile(profile);
        pm.saveProfileList(this);
        pm.saveProfile(this, profile);
    }

    private ProfileManager getProfileManager() {
        return ProfileManager.getInstance(this);
    }
}