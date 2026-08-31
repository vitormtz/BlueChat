package com.example.bluechat;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final String APP_NAME = "BlueChat";
    private static final UUID MY_UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66");
    private static final int STATE_CONNECTED = 1;
    private static final int STATE_MESSAGE_RECEIVED = 2;
    private static final int STATE_CONNECTION_FAILED = 3;
    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_PERMISSIONS = 2;
    private Button buttonEnableDiscoverable, buttonDiscover, buttonSend;
    private ListView listViewDevices;
    private TextView textViewStatus, textViewMessages;
    private EditText editTextMessage;
    private BluetoothAdapter bluetoothAdapter;
    private ArrayAdapter<String> adapterDevices;
    private List<BluetoothDevice> discoveredDevices;
    private Map<String, Integer> deviceRssiMap;
    private String[] permissions = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
    };
    private ServerClass serverClass;
    private ClientClass clientClass;
    private SendReceive sendReceive;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initializeUI();
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Este dispositivo não suporta Bluetooth", Toast.LENGTH_LONG).show();
            finish();
        }

        checkAndRequestPermissions();

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothDevice.ACTION_FOUND);
        intentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(bluetoothDiscoveryReceiver, intentFilter);

        discoveredDevices = new ArrayList<>();
        deviceRssiMap = new HashMap<>();
        adapterDevices = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        listViewDevices.setAdapter(adapterDevices);
        setupClickEvents();
    }

    private void initializeUI() {
        buttonEnableDiscoverable = findViewById(R.id.buttonEnableDiscoverable);
        buttonDiscover = findViewById(R.id.buttonDiscover);
        buttonSend = findViewById(R.id.buttonSend);
        listViewDevices = findViewById(R.id.listViewDevices);
        textViewStatus = findViewById(R.id.textViewStatus);
        textViewMessages = findViewById(R.id.textViewMessages);
        editTextMessage = findViewById(R.id.editTextMessage);
    }

    private void checkAndRequestPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(
                    this,
                    permissionsToRequest.toArray(new String[0]),
                    REQUEST_PERMISSIONS
            );
        }

        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            }
        }
    }

    private void setupClickEvents() {
        buttonEnableDiscoverable.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                enableDiscoverable();
            }
        });

        buttonDiscover.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                discoverDevices();
            }
        });

        buttonSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendMessage();
            }
        });

        listViewDevices.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                connectToDevice(position);
            }
        });
    }

    private void enableDiscoverable() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permissão de Bluetooth não concedida", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
        startActivity(discoverableIntent);

        textViewStatus.setText("Status: Aguardando conexão (Servidor)");
        serverClass = new ServerClass();
        serverClass.start();
    }

    private void discoverDevices() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permissão de escaneamento Bluetooth não concedida", Toast.LENGTH_SHORT).show();
            return;
        }

        textViewStatus.setText("Status: Procurando dispositivos...");
        adapterDevices.clear();
        discoveredDevices.clear();
        deviceRssiMap.clear();

        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
        bluetoothAdapter.startDiscovery();
    }

     private void updateDeviceList(BluetoothDevice device, int rssi) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        String deviceName = device.getName();
        if (deviceName == null) {
            deviceName = "Dispositivo desconhecido";
        }

        String signalStrength = "";
        if (rssi != Short.MIN_VALUE) {
            if (rssi > -50) {
                signalStrength = "Sinal Excelente";
            } else if (rssi > -70) {
                signalStrength = "Sinal Bom";
            } else if (rssi > -90) {
                signalStrength = "Sinal Médio";
            } else {
                signalStrength = "Sinal Fraco";
            }
        }

        String deviceInfo = deviceName + "\n" + device.getAddress();
        if (!signalStrength.isEmpty()) {
            deviceInfo += "\n" + signalStrength + " (" + rssi + " dBm)";
        }

        boolean found = false;
        for (int i = 0; i < adapterDevices.getCount(); i++) {
            if (adapterDevices.getItem(i).contains(device.getAddress())) {
                adapterDevices.remove(adapterDevices.getItem(i));
                adapterDevices.insert(deviceInfo, i);
                found = true;
                break;
            }
        }

        if (!found) {
            adapterDevices.add(deviceInfo);
        }

        adapterDevices.notifyDataSetChanged();
    }

    private void connectToDevice(int position) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        bluetoothAdapter.cancelDiscovery();

        textViewStatus.setText("Status: Conectando...");

        BluetoothDevice device = discoveredDevices.get(position);
        clientClass = new ClientClass(device);
        clientClass.start();
    }

    private void sendMessage() {
        String message = editTextMessage.getText().toString();
        if (!message.isEmpty() && sendReceive != null) {
            sendReceive.write(message.getBytes());

            textViewMessages.setText(textViewMessages.getText() + "\nEu: " + message);
            editTextMessage.setText("");
        }
    }

    private Handler handler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(@NonNull Message msg) {
            switch (msg.what) {
                case STATE_CONNECTED:
                    textViewStatus.setText("Status: Conectado a " + msg.obj);
                    break;
                case STATE_CONNECTION_FAILED:
                    textViewStatus.setText("Status: Falha na conexão");
                    break;
                case STATE_MESSAGE_RECEIVED:
                    byte[] readBuffer = (byte[]) msg.obj;
                    String receivedMessage = new String(readBuffer, 0, msg.arg1);
                    textViewMessages.setText(textViewMessages.getText() + "\nOutro: " + receivedMessage);
                    break;
            }
            return true;
        }
    });

    private final BroadcastReceiver bluetoothDiscoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);

                if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    return;
                }

                if (device != null) {
                    boolean isNewDevice = true;
                    for (BluetoothDevice d : discoveredDevices) {
                        if (d.getAddress().equals(device.getAddress())) {
                            isNewDevice = false;
                            break;
                        }
                    }

                    if (isNewDevice) {
                        discoveredDevices.add(device);
                    }

                    deviceRssiMap.put(device.getAddress(), rssi);

                    updateDeviceList(device, rssi);
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                textViewStatus.setText("Status: Busca concluída");
            }
        }
    };

    private class ServerClass extends Thread {
        private BluetoothServerSocket serverSocket;

        public ServerClass() {
            try {
                if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
                serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(APP_NAME, MY_UUID);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void run() {
            BluetoothSocket socket = null;

            while (socket == null) {
                try {
                    Message message = Message.obtain();
                    message.what = STATE_CONNECTED;
                    message.obj = "Aguardando...";
                    handler.sendMessage(message);

                    socket = serverSocket.accept();
                } catch (IOException e) {
                    e.printStackTrace();
                    Message message = Message.obtain();
                    message.what = STATE_CONNECTION_FAILED;
                    handler.sendMessage(message);
                    break;
                }

                if (socket != null) {
                    if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        continue;
                    }

                    Message message = Message.obtain();
                    message.what = STATE_CONNECTED;
                    message.obj = socket.getRemoteDevice().getName();
                    handler.sendMessage(message);

                    sendReceive = new SendReceive(socket);
                    sendReceive.start();
                    break;
                }
            }
        }
    }

    private class ClientClass extends Thread {
        private BluetoothSocket socket;
        private BluetoothDevice device;

        public ClientClass(BluetoothDevice device) {
            this.device = device;
            try {
                if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
                this.socket = device.createRfcommSocketToServiceRecord(MY_UUID);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void run() {
            try {
                if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
                socket.connect();
                Message message = Message.obtain();
                message.what = STATE_CONNECTED;
                message.obj = socket.getRemoteDevice().getName();
                handler.sendMessage(message);

                sendReceive = new SendReceive(socket);
                sendReceive.start();
            } catch (IOException e) {
                e.printStackTrace();
                Message message = Message.obtain();
                message.what = STATE_CONNECTION_FAILED;
                handler.sendMessage(message);
            }
        }
    }

    private class SendReceive extends Thread {
        private final BluetoothSocket bluetoothSocket;
        private final InputStream inputStream;
        private final OutputStream outputStream;

        public SendReceive(BluetoothSocket socket) {
            this.bluetoothSocket = socket;
            InputStream tempIn = null;
            OutputStream tempOut = null;

            try {
                tempIn = socket.getInputStream();
                tempOut = socket.getOutputStream();
            } catch (IOException e) {
                e.printStackTrace();
            }

            inputStream = tempIn;
            outputStream = tempOut;
        }

        public void run() {
            byte[] buffer = new byte[1024];
            int bytes;

            while (true) {
                try {
                    bytes = inputStream.read(buffer);
                    handler.obtainMessage(STATE_MESSAGE_RECEIVED, bytes, -1, buffer).sendToTarget();
                } catch (IOException e) {
                    e.printStackTrace();
                    break;
                }
            }
        }

        public void write(byte[] bytes) {
            try {
                outputStream.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(bluetoothDiscoveryReceiver);
    }
}