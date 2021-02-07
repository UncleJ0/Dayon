package mpo.dayon.assisted.gui;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.net.SocketException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import javax.net.ssl.*;
import javax.swing.*;

import mpo.dayon.assisted.capture.CaptureEngine;
import mpo.dayon.assisted.capture.CaptureEngineConfiguration;
import mpo.dayon.assisted.capture.RobotCaptureFactory;
import mpo.dayon.assisted.compressor.CompressorEngine;
import mpo.dayon.assisted.compressor.CompressorEngineConfiguration;
import mpo.dayon.assisted.control.NetworkControlMessageHandler;
import mpo.dayon.assisted.control.RobotNetworkControlMessageHandler;
import mpo.dayon.assisted.mouse.MouseEngine;
import mpo.dayon.assisted.network.NetworkAssistedEngine;
import mpo.dayon.assisted.network.NetworkAssistedEngineConfiguration;
import mpo.dayon.common.babylon.Babylon;
import mpo.dayon.common.error.FatalErrorHandler;
import mpo.dayon.common.error.KeyboardErrorHandler;
import mpo.dayon.common.event.Subscriber;
import mpo.dayon.common.gui.common.DialogFactory;
import mpo.dayon.common.log.Log;
import mpo.dayon.common.network.NetworkEngine;
import mpo.dayon.common.network.message.*;
import mpo.dayon.common.security.CustomTrustManager;
import mpo.dayon.common.utils.FileUtilities;
import mpo.dayon.common.utils.SystemUtilities;

public class Assisted implements Subscriber, ClipboardOwner {
	private AssistedFrame frame;

	private NetworkAssistedEngineConfiguration configuration;

	private CaptureEngine captureEngine;

	private CompressorEngine compressorEngine;

	public void configure() {
		final String lnf = SystemUtilities.getDefaultLookAndFeel();
		try {
			UIManager.setLookAndFeel(lnf);
		} catch (Exception ex) {
			Log.warn("Could not set the [" + lnf + "] L&F!", ex);
		}
	}

	public void start(String serverName, String portNumber) {
		frame = new AssistedFrame();

		FatalErrorHandler.attachFrame(frame);
		KeyboardErrorHandler.attachFrame(frame);

		frame.setVisible(true);

		// accept own cert, avoid PKIX path building exception
		SSLContext sc = null;
		try {
			sc = SSLContext.getInstance("TLS");
			sc.init(null, new TrustManager[] { new CustomTrustManager() }, null);
		} catch (NoSuchAlgorithmException | KeyManagementException e) {
			Log.error(e.getMessage());
			System.exit(1);
		}
		HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

		// these should not block as they are called from the network incoming message thread (!)
		final NetworkCaptureConfigurationMessageHandler captureConfigurationHandler = this::onCaptureEngineConfigured;
		final NetworkCompressorConfigurationMessageHandler compressorConfigurationHandler = this::onCompressorEngineConfigured;
		final NetworkClipboardRequestMessageHandler clipboardRequestHandler = this::onClipboardRequested;
		final NetworkControlMessageHandler controlHandler = new RobotNetworkControlMessageHandler();

		controlHandler.subscribe(this);

		final NetworkAssistedEngine networkEngine = new NetworkAssistedEngine(captureConfigurationHandler, compressorConfigurationHandler, controlHandler, clipboardRequestHandler, this);

		boolean connected = false;

		while (!connected) {
			configureConnection(serverName, portNumber);
			frame.onConnecting(configuration);
			networkEngine.configure(configuration);
			try {
				networkEngine.start();
				connected = true;
			} catch (SocketException e) {
				frame.onRefused(configuration);
				serverName = null;
				portNumber = null;
			} catch (NoSuchAlgorithmException | IOException | KeyManagementException e) {
				FatalErrorHandler.bye(e.getMessage(), e);
			}
		}
		networkEngine.sendHello();
		frame.onConnected();
	}

	private void configureConnection(String serverName, String portNumber) {
		if (SystemUtilities.isValidIpAddressOrHostName(serverName) && SystemUtilities.isValidPortNumber(portNumber)) {
			configuration = new NetworkAssistedEngineConfiguration(serverName, Integer.parseInt(portNumber));
		} else {
			configuration = new NetworkAssistedEngineConfiguration();

			final String ip = SystemUtilities.getStringProperty(null, "dayon.assistant.ipAddress", null);
			final int port = SystemUtilities.getIntProperty(null, "dayon.assistant.portNumber", -1);

			if ((ip == null || port == -1) && !requestConnectionSettings()) {
				Log.info("Bye!");
				System.exit(0);
			}
		}
		Log.info("Configuration " + configuration);
	}

	@Override
	public void lostOwnership(Clipboard clipboard, Transferable transferable) {
		Log.error("Lost clipboard ownership");
	}

	private boolean requestConnectionSettings() {
		JPanel connectionSettingsDialog = new JPanel();

		connectionSettingsDialog.setLayout(new GridLayout(3, 2, 10, 10));

		final JLabel assistantIpAddress = new JLabel(Babylon.translate("connection.settings.assistantIpAddress"));
		final JTextField assistantIpAddressTextField = new JTextField();
		assistantIpAddressTextField.setText(configuration.getServerName());
		assistantIpAddressTextField.addMouseListener(clearTextOnDoubleClick(assistantIpAddressTextField));

		connectionSettingsDialog.add(assistantIpAddress);
		connectionSettingsDialog.add(assistantIpAddressTextField);

		final JLabel assistantPortNumberLbl = new JLabel(Babylon.translate("connection.settings.assistantPortNumber"));
		final JTextField assistantPortNumberTextField = new JTextField();
		assistantPortNumberTextField.setText(String.valueOf(configuration.getServerPort()));
		assistantPortNumberTextField.addMouseListener(clearTextOnDoubleClick(assistantPortNumberTextField));

		connectionSettingsDialog.add(assistantPortNumberLbl);
		connectionSettingsDialog.add(assistantPortNumberTextField);

		final boolean ok = DialogFactory.showOkCancel(frame, Babylon.translate("connection.settings"), connectionSettingsDialog, () -> {
            final String ipAddress = assistantIpAddressTextField.getText();
            if (ipAddress.isEmpty()) {
                return Babylon.translate("connection.settings.emptyIpAddress");
            } else if (!SystemUtilities.isValidIpAddressOrHostName(ipAddress.trim())) {
            	return Babylon.translate("connection.settings.invalidIpAddress");
            }

            final String portNumber = assistantPortNumberTextField.getText();
            if (portNumber.isEmpty()) {
                return Babylon.translate("connection.settings.emptyPortNumber");
            }
            return SystemUtilities.isValidPortNumber(portNumber.trim()) ? null : Babylon.translate("connection.settings.invalidPortNumber");
        });

		if (ok) {
			final NetworkAssistedEngineConfiguration xconfiguration = new NetworkAssistedEngineConfiguration(assistantIpAddressTextField.getText().trim(),
					Integer.parseInt(assistantPortNumberTextField.getText().trim()));
			if (!xconfiguration.equals(configuration)) {
				configuration = xconfiguration;
				configuration.persist();
			}
		}
		return ok;
	}

	private MouseAdapter clearTextOnDoubleClick(JTextField textField) {
		return new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2) {
					textField.setText(null);
				}
			}
		};
	}

	/**
	 * Should not block as called from the network incoming message thread (!)
	 */
	private void onCaptureEngineConfigured(NetworkEngine engine, NetworkCaptureConfigurationMessage configuration) {
		final CaptureEngineConfiguration captureEngineConfiguration = configuration.getConfiguration();

		Log.info("Capture configuration received " + captureEngineConfiguration);

		if (captureEngine != null) {
			captureEngine.reconfigure(captureEngineConfiguration);
			return;
		}

		// First time we receive a configuration from the assistant (!)

		// Setup the mouse engine (no need before I guess)
		final MouseEngine mouseEngine = new MouseEngine();
		mouseEngine.addListener((NetworkAssistedEngine) engine);
		mouseEngine.start();

		captureEngine = new CaptureEngine(new RobotCaptureFactory());
		captureEngine.configure(captureEngineConfiguration);

		if (compressorEngine != null) {
			captureEngine.addListener(compressorEngine);
		}

		captureEngine.start();
	}

	/**
	 * Should not block as called from the network incoming message thread (!)
	 */
	private void onCompressorEngineConfigured(NetworkEngine engine, NetworkCompressorConfigurationMessage configuration) {
		final CompressorEngineConfiguration compressorEngineConfiguration = configuration.getConfiguration();

		Log.info("Compressor configuration received " + compressorEngineConfiguration);

		if (compressorEngine != null) {
			compressorEngine.reconfigure(compressorEngineConfiguration);
			return;
		}

		compressorEngine = new CompressorEngine();
		compressorEngine.configure(compressorEngineConfiguration);
		compressorEngine.addListener((NetworkAssistedEngine) engine);
		compressorEngine.start(1);

		if (captureEngine != null) {
			captureEngine.addListener(compressorEngine);
		}
	}

	/**
	 * Should not block as called from the network incoming message thread (!)
	 */
	private void onClipboardRequested(NetworkAssistedEngine engine) {

		Log.info("Clipboard transfer request received");
		Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
		Transferable transferable = clipboard.getContents(this);

		if (transferable == null) return;

		try {
			if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
				// noinspection unchecked
				List<File> files = (List<File>) clipboard.getData(DataFlavor.javaFileListFlavor);
				if (!files.isEmpty()) {
					final long totalFilesSize = FileUtilities.calculateTotalFileSize(files);
					Log.debug("Clipboard contains files with size: " + totalFilesSize );
					engine.sendClipboardFiles(files, totalFilesSize, files.get(0).getParent());
				}
			} else if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
				// noinspection unchecked
				String text = (String) clipboard.getData(DataFlavor.stringFlavor);
				Log.debug("Clipboard contains text: " + text);
				engine.sendClipboardText(text, text.getBytes().length);
			}
		} catch (IOException | UnsupportedFlavorException ex) {
			Log.error("Clipboard error " + ex.getMessage());
		}
	}

	@Override
	public void digest(String message) {
		KeyboardErrorHandler.warn(String.valueOf(message));
	}

}
