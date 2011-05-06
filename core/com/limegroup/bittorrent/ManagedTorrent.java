package com.limegroup.bittorrent;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.gudy.azureus2.core3.disk.DiskManagerFileInfo;
import org.gudy.azureus2.core3.download.DownloadManager;
import org.gudy.azureus2.core3.download.DownloadManagerException;
import org.gudy.azureus2.core3.download.DownloadManagerListener;
import org.gudy.azureus2.core3.download.DownloadManagerPeerListener;
import org.gudy.azureus2.core3.download.DownloadManagerPieceListener;
import org.gudy.azureus2.core3.download.DownloadManagerStats;
import org.gudy.azureus2.core3.global.GlobalManagerDownloadRemovalVetoException;
import org.gudy.azureus2.core3.peer.PEPeer;
import org.gudy.azureus2.core3.peer.PEPeerManager;
import org.gudy.azureus2.core3.peer.PEPiece;
import org.gudy.azureus2.core3.torrent.TOTorrent;
import org.gudy.azureus2.core3.util.DisplayFormatters;
import org.gudy.azureus2.core3.util.TorrentUtils;
import org.limewire.concurrent.ExecutorsHelper;
import org.limewire.concurrent.SyncWrapper;
import org.limewire.io.DiskException;
import org.limewire.io.NetworkInstanceUtils;
import org.limewire.service.ErrorService;

import com.aelitis.azureus.core.AzureusCore;
import com.aelitis.azureus.core.AzureusCoreComponent;
import com.aelitis.azureus.core.AzureusCoreException;
import com.aelitis.azureus.core.AzureusCoreLifecycleListener;
import com.frostwire.bittorrent.AzureusStarter;
import com.limegroup.bittorrent.choking.ChokerFactory;
import com.limegroup.bittorrent.disk.DiskManagerListener;
import com.limegroup.bittorrent.handshaking.BTConnectionFetcher;
import com.limegroup.bittorrent.handshaking.BTConnectionFetcherFactory;
import com.limegroup.bittorrent.settings.BittorrentSettings;
import com.limegroup.bittorrent.tracking.TrackerManagerFactory;
import com.limegroup.gnutella.FileManager;
import com.limegroup.gnutella.NetworkManager;
import com.limegroup.gnutella.URN;
import com.limegroup.gnutella.auth.ContentManager;
import com.limegroup.gnutella.filters.IPFilter;
import com.limegroup.gnutella.settings.SharingSettings;
import com.limegroup.gnutella.util.EventDispatcher;

/**
 * Class that keeps track of state relevant to a single torrent.
 * 
 * It manages various components relevant to the torrent download such as the
 * Choker, Connection Fetcher, Verifying Folder.
 * 
 * It keeps track of the known and connected peers and contains the logic for
 * starting and stopping the torrent.
 */
public class ManagedTorrent implements Torrent, DiskManagerListener,
		BTLinkListener {

	private static final Log LOG = LogFactory.getLog(ManagedTorrent.class);

	/**
	 * A shared processing queue for disk-related tasks.
	 */
	private static final ExecutorService DEFAULT_DISK_INVOKER = ExecutorsHelper
			.newProcessingQueue("ManagedTorrent");

	/** the executor of tasks involving network io. */
	// private final ScheduledExecutorService networkInvoker;

	/**
	 * Executor that changes the state of this torrent and does the moving of
	 * files to the complete location, and other tasks involving disk io.
	 */
	private ExecutorService diskInvoker = DEFAULT_DISK_INVOKER;

	/**
	 * The list of known good TorrentLocations that we are not connected or
	 * connecting to at the moment
	 */
	// private Set<TorrentLocation> _peers;

	/**
	 * the meta info for this torrent
	 */
	private BTMetaInfo _info;

	/**
	 * The manager of disk operations.
	 */
	// private volatile TorrentDiskManager _folder;

	/**
	 * The manager of tracker requests.
	 */
	// private final TrackerManager trackerManager;

	/**
	 * Factory for our connection fetcher
	 */
	// private final BTConnectionFetcherFactory connectionFetcherFactory;

	/**
	 * The fetcher of connections.
	 */
	// private volatile BTConnectionFetcher _connectionFetcher;

	/** Manager of the BT links of this torrent */
	private final BTLinkManager linkManager;

	/** Factory for our chokers */
	// private final ChokerFactory chokerFactory;

	/**
	 * The manager of choking logic
	 */
	// private Choker choker;

	/**
	 * Locking this->state.getLock() ok.
	 */
	private final SyncWrapper<TorrentState> state = new SyncWrapper<TorrentState>(
			TorrentState.QUEUED);

	/** Event dispatcher for events generated by this torrent */
	private final EventDispatcher<TorrentEvent, TorrentEventListener> dispatcher;

	private final TorrentManager torrentManager;

	private final FileManager fileManager;

	private AzureusCore _azureusCore;

	private DownloadManager _manager;

	private File _torrentFile;

	/**
	 * It seems that when we try to resume torrents, they're on a STOPPED state, FrostWire looks at this as aborted.
	 * We have this flag here to ignore the STOPPED state during startup, afterwards, it's treated normally.
	 * @see onStateChanged()
	 */
	private boolean _ignoreStopped = true;
	
	private boolean _changedSaveDir = false;
	
	private String _newSaveDir;

	private boolean _overwrite;
	
	//TODO: Fix amount of total downloaded by keeping the bytes we download here. Otherwise use stats.
	private long _totalDownloaded;
	private boolean _hasBeenPaused;

	/**
	 * Constructs new ManagedTorrent
	 * 
	 * @param info
	 *            the <tt>BTMetaInfo</tt> for this torrent
	 * @param dispatcher
	 *            a dispatcher for events generated by this torrent
	 * @param networkInvoker
	 *            a <tt>SchedulingThreadPool</tt> to execute network tasks on
	 * @param fileManager
	 * @param diskInvoker
	 *            a <tt>SchedulingThreadPool</tt> to execute disk tasks on
	 */
	ManagedTorrent(TorrentContext context,
			EventDispatcher<TorrentEvent, TorrentEventListener> dispatcher,
			ScheduledExecutorService networkInvoker,
			NetworkManager networkManager,
			TrackerManagerFactory trackerManagerFactory,
			ChokerFactory chokerFactory,
			BTLinkManagerFactory linkManagerFactory,
			BTConnectionFetcherFactory connectionFetcherFactory,
			ContentManager contentManager, IPFilter ipFilter,
			TorrentManager torrentManager, FileManager fileManager,
			NetworkInstanceUtils networkInstanceUtils, boolean overwrite) {
		this.dispatcher = dispatcher;
		this.torrentManager = torrentManager;
		this.fileManager = fileManager;
		_info = context.getMetaInfo();
		linkManager = linkManagerFactory.getLinkManager();
		_overwrite = overwrite;
		_hasBeenPaused = false;
		azureusInit();
		
	}
	
	/**
	 * notification that a request to the tracker(s) has started.
	 */
	public void setScraping() {
		synchronized (state.getLock()) {
			if (state.get() == TorrentState.WAITING_FOR_TRACKER)
				state.set(TorrentState.SCRAPING);
		}
	}

	/**
	 * Accessor for the info hash
	 * 
	 * @return byte[] containing the info hash
	 */
	public byte[] getInfoHash() {
		return _info.getInfoHash();
	}

	public byte[] getSha1() {
		return getInfoHash();
	}

	public URN getURN() {
		return _info.getURN();
	}

	/**
	 * Accessor for meta info
	 * 
	 * @return <tt>BTMetaInfo</tt> for this torrent
	 */
	public BTMetaInfo getMetaInfo() {
		return _info;
	}

	/**
	 * @return the <tt>TorrentContext</tt> for this torrent
	 */
	public TorrentContext getContext() {
		LOG.debug("getContext() invoked, returning null");
		return null;
		// return context;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.limegroup.bittorrent.Torrent#isComplete()
	 */
	public boolean isComplete() {
		// return state.get() != TorrentState.DISK_PROBLEM &&
		// _folder.isComplete();
		
		//IDEA: Use manager.getStats().getCompleted() == 1000
		
		return state.get() != TorrentState.DISK_PROBLEM && 
			   _manager != null &&
			   _manager.isDownloadComplete(false) && !_overwrite;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.limegroup.bittorrent.Torrent#start()
	 */
	public void start() {
		//if (LOG.isDebugEnabled())
		//	LOG.debug("requesting torrent start", new Exception());
	    
	    if (_hasBeenPaused) {
	        return;
	    }

		synchronized (state.getLock()) {
			if (state.get() != TorrentState.QUEUED)
				throw new IllegalStateException(
						"torrent should be queued but is " + state.get());
		}
		dispatchEvent(TorrentEvent.Type.STARTING);

		diskInvoker.execute(new Runnable() {
			public void run() {

				if (state.get() != TorrentState.QUEUED) // something happened,
														// do not start.
					return;

				LOG.debug("executing torrent start");

				// initializeTorrent();
				initializeAzureusTorrent();
				initializeFolder();
				if (state.get() == TorrentState.DISK_PROBLEM)
					return;

				dispatchEvent(TorrentEvent.Type.STARTED);

				TorrentState s = state.get();
				if (s == TorrentState.SEEDING || s == TorrentState.VERIFYING)
					return;

				validateTorrent();
				startConnecting();
			}
		});

	}
	
	private void initializeTorrentFile() {
		if (getTorrentDataFile().exists() && 
				getTorrentDataFile().length() > 0 &&
				getTorrentDataFile().isFile())
				_torrentFile = getTorrentDataFile();
			else
				createTorrentFile(); //initializes _torrentFile	
	}
	
	private void azureusInit() {
		if (_azureusCore == null) {
			try {
				_azureusCore = AzureusStarter.getAzureusCore();
			} catch (Exception shhLetItHappen) {
				
			}
		}
	}


	private void createAzureusDownloadManager() throws Exception {
		if (_azureusCore == null) {
			azureusInit();
		}
	
		while (!_azureusCore.isStarted()) {
			LOG.debug("createAzureusDownloadManager: Waiting for azureusCore to start - sleeping...");
			Thread.sleep(500);
		} 
		
		initializeTorrentFile();
		
		int MAX_TRIES = 20;
		do {
		    
		    File saveDir = SharingSettings.TORRENT_DATA_DIR_SETTING.getValue();
			if (!saveDir.exists()) {
			    saveDir.mkdirs();
			}
			
			TOTorrent torrent = TorrentUtils.readFromFile(_torrentFile, false);
			
			if ((_manager = _azureusCore.getGlobalManager().getDownloadManager(torrent)) == null) {			
			    _manager = _azureusCore.getGlobalManager().addDownloadManager(
			            _torrentFile.getCanonicalPath(),
			            saveDir.getCanonicalPath());
			}
			
			LOG.debug("createdAzureusDownloadManger - Azureus Save Location is:\n>> " + _manager.getSaveLocation().getCanonicalPath());

			if (_manager == null) {
				
				LOG.debug("createAzureusDownloadManager We still don't have a manager - sleeping...");
				Thread.sleep(500);
				MAX_TRIES--;
			}
			
			if (MAX_TRIES==0 && _manager == null) {
				LOG.debug("createAzureusDownloadManager() - Tried too many times to get a download manager, get me out of here.");
				return;
			}
		} while (_manager == null);
	}

	/**
	 * Initializes some state relevant to the torrent
	 */
	/*
	 * private void initializeTorrent() { _peers =
	 * Collections.synchronizedSet(new StrictIpPortSet<TorrentLocation>());
	 * choker = chokerFactory.getChoker(linkManager, false); _connectionFetcher
	 * = connectionFetcherFactory.getBTConnectionFetcher(this); }
	 */

	private void initializeAzureusTorrent() {
		try {
			createAzureusDownloadManager();
			
			File saveLocation = _manager.getSaveLocation();
			boolean saveFolderNotThere = !saveLocation.exists();
			boolean saveFolderIsEmpty = saveLocation.list() == null;
			
			if (saveFolderNotThere || saveFolderIsEmpty || _overwrite) {
				//_azureusCore.getGlobalManager().removeDownloadManager(_manager,
				//		true, true);
				//createAzureusDownloadManager();
				_overwrite = false; // For proper isComplete logic.
			}
			
			if (_manager == null)
				throw new Exception("_manager was null (from azureusCore)");

			_manager.addListener(new DownloadManagerListener() {

				@Override
				public void stateChanged(DownloadManager manager, int intState) {
					onStateChanged(_manager, _manager.getState());
				}
				
				@Override
				public void positionChanged(DownloadManager download,
						int oldPosition, int newPosition) {
				}

				@Override
				public void filePriorityChanged(DownloadManager download,
						DiskManagerFileInfo file) {
				}

				@Override
				public void downloadComplete(DownloadManager manager) {
					// TODO Auto-generated method stub
					LOG.debug("(Az)DownloadManagerListener - downloadComplete() - dispatch COMPLETE");
					
					synchronized(state.getLock()) {
						state.set(TorrentState.SEEDING);
						dispatchEvent(TorrentEvent.Type.COMPLETE);
					}
					addToLibrary();

					//stop seeding if we don't have to.
					if (!SharingSettings.SEED_FINISHED_TORRENTS.getValue()) {
						stopSeeding();
					}

				}

				@Override
				public void completionChanged(DownloadManager manager,
						boolean bCompleted) {
					// TODO Auto-generated method stub
					//state.set(TorrentState.SEEDING);
					LOG.debug("(Az)DownloadManagerListener - completionChanged - " + manager.getStats().getCompleted() + "% completed.");
				}
			});
			
			_manager.addPeerListener(new DownloadManagerPeerListener() {
				
				@Override
				public void peerRemoved(PEPeer peer) {
					//LOG.debug("Peer removed " + peer.getIp());
					onStateChanged(_manager, _manager.getState());
				}
				
				@Override
				public void peerManagerWillBeAdded(PEPeerManager manager) {
					// TODO Auto-generated method stub
					onStateChanged(_manager, _manager.getState());
				}
				
				@Override
				public void peerManagerRemoved(PEPeerManager manager) {
					// TODO Auto-generated method stub
					onStateChanged(_manager, _manager.getState());
					
				}
				
				@Override
				public void peerManagerAdded(PEPeerManager manager) {
					// TODO Auto-generated method stub
					onStateChanged(_manager, _manager.getState());
					
				}
				
				@Override
				public void peerAdded(PEPeer peer) {
					// TODO Auto-generated method stub
					//LOG.debug("Peer added " + peer.getIp());
					onStateChanged(_manager, _manager.getState());
				}
			});
			
			_manager.addPieceListener(new DownloadManagerPieceListener() {
				
				@Override
				public void pieceRemoved(PEPiece piece) {
					//TODO: For further UI visualization purposes
				}
				
				@Override
				public void pieceAdded(PEPiece piece) {
					//TODO: For further UI visualization purposes
				}
			});

			//Doing this instead of handling FrostWire's GUI Lifecycle event.
			//Azureus Core gets those events already, so when it's about to stop, we pause() ourselves
			//and flag _shuttingdown = true so that we don't listen to anymore events.
			_azureusCore.addLifecycleListener(new AzureusCoreLifecycleListener() {
				@Override
				public void stopping(AzureusCore core) {
					if (!_shuttingdown) {
						LOG.debug("Nobody else will be able to pause, but me now...");
						
						synchronized(_shuttingdown) {
							_shuttingdown = true;
						}
						pause();
					}
				}

				@Override
				public boolean syncInvokeRequired() {
					return false;
				}
				
				@Override
				public void stopped(AzureusCore core) {
				}
				
				@Override
				public boolean stopRequested(AzureusCore core) throws AzureusCoreException {
					return false;
				}
				
				@Override
				public void started(AzureusCore core) {
					
				}
				
				@Override
				public boolean restartRequested(AzureusCore core)
						throws AzureusCoreException {
					return false;
				}
				
				@Override
				public boolean requiresPluginInitCompleteBeforeStartedEvent() {
					return false;
				}
				
				@Override
				public void componentCreated(AzureusCore core,
						AzureusCoreComponent component) {
				}
			});
						
			_manager.setStateWaiting();
			
		} catch (Exception e) {
			LOG.error("Error starting torrent download", e);
		}
	}
	
	protected void stopSeeding() {
		removeFromAzureus();
		dispatchEvent(TorrentEvent.Type.STOP_SEEDING);
		//UploadMediator.instance().stopSeeding(ManagedTorrent.this);
	}

	private Boolean _shuttingdown = false;
		
	private void onStateChanged(DownloadManager manager, int intState) {
		if (_shuttingdown){
			LOG.debug("onStateChanged("+intState+") - Talk to the hand!");
			return;
		}
		// TorrentEvent.
		// (FrostWire World)
		// TorrentEvent.Type.COMPLETE
		// TorrentEvent.Type.DOWNLOADING
		// TorrentEvent.Type.STARTED
		// TorrentEvent.Type.STARTING
		// TorrentEvent.Type.STOP_APPROVED
		// TorrentEvent.Type.STOP_REQUESTED
		// TorrentEvent.Type.STOPPED

		// (Azureus World)
		// DownloadManager.STATE_ALLOCATING
		// DownloadManager.STATE_CHECKING
		// DownloadManager.STATE_CLOSED
		// DownloadManager.STATE_DOWNLOADING
		// DownloadManager.STATE_ERROR
		// DownloadManager.STATE_FINISHING
		// DownloadManager.STATE_INITIALIZED
		// DownloadManager.STATE_INITIALIZING
		// DownloadManager.STATE_QUEUED
		// DownloadManager.STATE_READY
		// DownloadManager.STATE_SEEDING
		// DownloadManager.STATE_STOPPED
		// DownloadManager.STATE_WAITING
		//LOG.debug("stateChanged - " + intState);
		
		if (intState == DownloadManager.STATE_READY) {
			LOG.debug("State READY (setting state to WAITING_FOR_TRACKER)");
			_manager.startDownload();
			dispatchEvent(TorrentEvent.Type.STARTED);
		} else if (intState == DownloadManager.STATE_ALLOCATING
				|| intState == DownloadManager.STATE_INITIALIZING) {
			synchronized(state.getLock()) {
				state.set(TorrentState.CONNECTING);
			}
			dispatchEvent(TorrentEvent.Type.STARTING);
		} else if (intState == DownloadManager.STATE_INITIALIZED) {
			synchronized(state.getLock()) {
				state.set(TorrentState.WAITING_FOR_TRACKER);
			}
			dispatchEvent(TorrentEvent.Type.STARTED);
		} else if (intState == DownloadManager.STATE_SEEDING) {
			synchronized(state.getLock()) {
				state.set(TorrentState.SEEDING);
			}
			dispatchEvent(TorrentEvent.Type.STARTED);
		} else if (intState == DownloadManager.STATE_STOPPED && !_ignoreStopped) {
			if (!state.get().equals(TorrentState.PAUSED)) {
				synchronized(state.getLock()) {
					state.set(TorrentState.STOPPED);
				}
				dispatchEvent(TorrentEvent.Type.STOPPED);
			}
		} else if (intState == DownloadManager.STATE_DOWNLOADING) {
			_ignoreStopped = false;//ugly hack
			synchronized(state.getLock()) {
				state.set(TorrentState.DOWNLOADING);
			}
			dispatchEvent(TorrentEvent.Type.DOWNLOADING);
		} else if (intState == DownloadManager.STATE_ERROR) {
			synchronized(state.getLock()) {
				state.set(TorrentState.STOPPED);
			}
			dispatchEvent(TorrentEvent.Type.STOPPED);
		} else if (intState == DownloadManager.STATE_WAITING) {
		    _manager.initialize();
		}

		//printAzTorrentDownloadStats();

	} //stateChanged

	@SuppressWarnings("unused")
	private void printAzTorrentDownloadStats() {
		StringBuffer buf = new StringBuffer();
		buf.append(" Completed:");
		
		DownloadManagerStats stats = _manager.getStats();

		int completed = stats.getCompleted();
		buf.append(completed / 10);
		buf.append('.');
		buf.append(completed % 10);
		buf.append('%');
		buf.append(" Seeds:");
		buf.append(_manager.getNbSeeds());
		buf.append(" Peers:");
		buf.append(_manager.getNbPeers());
		buf.append(" Downloaded:");
		buf.append(DisplayFormatters.formatDownloaded(stats));
		buf.append(" Uploaded:");
		buf.append(DisplayFormatters.formatByteCountToKiBEtc(stats
				.getTotalDataBytesSent()));
		buf.append(" DSpeed:");
		buf.append(DisplayFormatters.formatByteCountToKiBEtcPerSec(stats
				.getDataReceiveRate()));
		buf.append(" USpeed:");
		buf.append(DisplayFormatters.formatByteCountToKiBEtcPerSec(stats
				.getDataSendRate()));
		buf.append(" TrackerStatus:");
		buf.append(_manager.getTrackerStatus());
		while (buf.length() < 80) {
			buf.append(' ');
		}
		
		buf.append(" TO:");
		buf.append(_info.getName());
		
		System.out.println(buf.toString());		
	}
	

	private void validateTorrent() {
		LOG.debug("validateTorrent() invoked, doing nothing");
	}

	/**
	 * Starts the tracker request if necessary and the fetching of connections.
	 */
	private void startConnecting() {
		
		//boolean shouldFetch = false;
		synchronized (state.getLock()) {
			//not working with new logic.
			//if (state.get() != TorrentState.QUEUED)
			//	return;

			// kick off connectors if we already have some addresses
			if (getNumPeers() > 0) {
				state.set(TorrentState.CONNECTING);
				//shouldFetch = true;
			} else
				state.set(TorrentState.SCRAPING);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.limegroup.bittorrent.Torrent#stop()
	 */
	public synchronized void stop() {

		if (!isActive()) {
			throw new IllegalStateException(
					"torrent cannot be stopped in state " + state.get());
		}
		state.set(TorrentState.STOPPED);
		stopImpl();
	}
	
	/**
	 * Performs the actual stop.
	 */
	private void stopImpl() {
		//if (!stopState())
		//	throw new IllegalStateException("stopping in wrong state "
		//			+ state.get());
	
		stopImpl(false);
	}
	
	private synchronized void stopImpl(boolean pause) {
		if (_manager != null) {
			_manager.stopIt(DownloadManager.STATE_STOPPED, false, false);
		}
	
		dispatchEvent(TorrentEvent.Type.STOPPED);
		LOG.debug("Torrent stopped on stopImpl()!");
	}


	/*
	 * (non-Javadoc)
	 * 
	 * @see com.limegroup.bittorrent.DiskManagerListener#diskExceptionHappened()
	 */
	public synchronized void diskExceptionHappened(DiskException e) {
		synchronized (state.getLock()) {
			if (state.get() == TorrentState.DISK_PROBLEM)
				return;
			state.set(TorrentState.DISK_PROBLEM);
		}
		stopImpl();
		if (BittorrentSettings.REPORT_DISK_PROBLEMS.getBoolean())
			ErrorService.error(e);
	}

	private void dispatchEvent(TorrentEvent.Type type, String description) {
		TorrentEvent evt = new TorrentEvent(this, type, this, description);
		dispatcher.dispatchEvent(evt);
	}

	private void dispatchEvent(TorrentEvent.Type type) {
		dispatchEvent(type, null);
	}

	/**
	 * @return if the current state is a stopped state.
	 */
	private boolean stopState() {
		switch (state.get()) {
		case PAUSED:
		case STOPPED:
		case DISK_PROBLEM:
		case TRACKER_FAILURE:
		case INVALID:
			return true;
		}
		return false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.limegroup.bittorrent.Torrent#pause()
	 */
	public synchronized void pause() {
		try {
			_hasBeenPaused = true;
			boolean wasActive = false;
			synchronized (state.getLock()) {
				if (!isActive() && state.get() != TorrentState.QUEUED) {
					state.set(TorrentState.PAUSED);
					return;
				}
	
				wasActive = isActive();
				state.set(TorrentState.PAUSED);
				LOG.debug("ManagedTorrent.pause() - set state to TorrentState.PAUSED");
			}
			
			if (wasActive) {
				LOG.debug("ManagedTorrent.pause() - wasActive=true, invoking stopImpl()");
				stopImpl(true);
			}
		} finally {
			LOG.debug("TIME: "+ System.currentTimeMillis()+ "ManagedTorrent.pause() - The torrent state at the end was: " + state.get()); 
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.limegroup.bittorrent.Torrent#resume()
	 */
	public boolean resume() {
		synchronized (state.getLock()) {
			switch (state.get()) {
				case PAUSED:
				case TRACKER_FAILURE:
				case STOPPED:
					if (_manager != null) {
						state.set(TorrentState.QUEUED);
						_manager.setStateWaiting();
					}
					return true;
				default:
					return false;
			}
		}
	}

	/**
	 * notification that a connection was closed.
	 */
	public void linkClosed(BTLink btc) {
		LOG.debug("linkClosed() invoked, doing nothing");
	}

	public void linkInterested(BTLink interested) {
		if (!interested.isChoked())
			rechoke();
	}

	public void linkNotInterested(BTLink notInterested) {
		if (!notInterested.isChoked())
			rechoke();
	}

	public void trackerRequestFailed() {
		synchronized (state.getLock()) {
			if (state.get() == TorrentState.SCRAPING)
				state.set(TorrentState.WAITING_FOR_TRACKER);
		}
	}

	/**
	 * Initializes the verifying folder
	 */
	private void initializeFolder() {
		LOG.debug("initializeFolder() invoked, doing nothing.");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.limegroup.bittorrent.DiskManagerListener#verificationComplete()
	 */
	public void verificationComplete() {
		LOG.debug("verificationComplete() invoked, this should not be invoked in theory.");
		diskInvoker.execute(new Runnable() {
			public void run() {
				synchronized (state.getLock()) {
					if (state.get() != TorrentState.VERIFYING)
						return;
					state.set(TorrentState.QUEUED);
				}
				//startConnecting();
				//if (_folder.isComplete())
				//	completeTorrentDownload();
			}
		});
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.limegroup.bittorrent.DiskManagerListener#notifyOfComplete(int)
	 */
	public void chunkVerified(int in) {
		/*
		if (LOG.isDebugEnabled())
			LOG.debug("got completed chunk " + in);

		if (_folder.isVerifying())
			return;

		final BTHave have = new BTHave(in);
		Runnable haveNotifier = new Runnable() {
			public void run() {
				linkManager.sendHave(have);
			}
		};
		networkInvoker.execute(haveNotifier);

		if (_folder.isComplete()) {
			LOG.info("file is complete");
			diskInvoker.execute(new Runnable() {
				public void run() {
					if (isDownloading())
						completeTorrentDownload();
				}
			});
		}
		*/
	}

	/**
	 * @return the state of this torrent
	 */
	public TorrentState getState() {
		return state.get();
	}

	/**
	 * adds location to try
	 * 
	 * @param to
	 *            a TorrentLocation for this download
	 */
	public void addEndpoint(TorrentLocation to) {
		LOG.debug("addEndpoint() invoked, should not be used.");
		/*
		if (_peers.contains(to) || linkManager.isConnectedTo(to))
			return;
		if (!ipFilter.allow(to.getAddress()))
			return;
		if (networkInstanceUtils.isMe(to.getAddress(), to.getPort()))
			return;
		if (_peers.add(to)) {
			synchronized (state.getLock()) {
				if (state.get() == TorrentState.SCRAPING)
					state.set(TorrentState.CONNECTING);
			}
			_connectionFetcher.fetch();
		}
		*/
	}

	/**
	 * Stops the torrent because of tracker failure.
	 */
	public synchronized void stopVoluntarily() {
		boolean stop = false;
		synchronized (state.getLock()) {
			if (!isActive())
				return;
			if (state.get() != TorrentState.SEEDING) {
				state.set(TorrentState.TRACKER_FAILURE);
				stop = true;
			}
		}
		if (stop)
			stopImpl();
	}

	/**
	 * @return true if we need to fetch any more connections
	 */
	public boolean needsMoreConnections() {
		LOG.debug("needsMoreConnections() invoked (always returning false)");
		return false;
		/*
		if (!isActive())
			return false;

		// if we are complete, do not open any sockets - the active torrents
		// will need them.
		if (isComplete() && torrentManager.hasNonSeeding())
			return false;

		// provision some slots for incoming connections unless we're firewalled
		// https://hal.inria.fr/inria-00162088/en/ recommends 1/2, we'll do 3/5
		int limit = TorrentManager.getMaxTorrentConnections();
		if (networkManager.acceptedIncomingConnection())
			limit = limit * 3 / 5;
		return linkManager.getNumConnections() < limit;
		*/
	}

	/**
	 * @return true if a fetched connection should be added.
	 */
	public boolean shouldAddConnection(TorrentLocation loc) {
		LOG.debug("needsMoreConnections() invoked");
		return false;
		/**
		if (linkManager.isConnectedTo(loc))
			return false;
		return linkManager.getNumConnections() < TorrentManager
				.getMaxTorrentConnections();
				*/
	}

	/**
	 * adds a fetched connection
	 * 
	 * @return true if it was added
	 */
	public boolean addConnection(final BTLink btc) {
		if (LOG.isDebugEnabled())
			LOG.debug("trying to add connection " + btc.toString());

		boolean shouldAdd = false;
		synchronized (state.getLock()) {
			switch (state.get()) {
			case CONNECTING:
			case SCRAPING:
			case WAITING_FOR_TRACKER:
				state.set(TorrentState.DOWNLOADING);
				dispatchEvent(TorrentEvent.Type.DOWNLOADING);
			case DOWNLOADING:
			case SEEDING:
				shouldAdd = true;
			}
		}

		if (!shouldAdd)
			return false;

		linkManager.addLink(btc);
		//_peers.remove(btc.getEndpoint());
		if (LOG.isDebugEnabled())
			LOG.debug("addConnection() invoked. Added connection to linkManager " + btc.toString());
		return true;
	}


	/**
	 * This happens when the torrent 
	 */
	private void addToLibrary() {
		if (_manager == null)
			return;
		
		if (_changedSaveDir) {
			
			try {
				_manager.moveDataFiles(new File(_newSaveDir));
			} catch (DownloadManagerException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			//File f = new File(_newSaveDir);
			//if (!(f.exists() && f.isDirectory())) {
				//f.mkdir();
				
			//	File from = _manager.getSaveLocation();
			//	File to = f;
			//	FileUtil.renameFile(from, to);
			//}
		}
		
		//THE LOGIC AHEAD USED TO SHARE TORRENT CONTENTS IN GNUTELLA.
		//DOING THIS COULD LEAD TO CONTRADICTORY TO FILE SHARING POLICIES SET BY THE USER.
		//TORRENTS SHALL BE SEEDED IN BITTORRENT, NOT SHARED UNLESS THE USER
		//MOVES THE FILES TO A SHARED FOLDER.
		
		/**
		boolean force = SharingSettings.SHARE_DOWNLOADED_FILES_IN_NON_SHARED_DIRECTORIES
				.getValue();

		File _completeFile = _manager.getSaveLocation();
		
		//File _completeFile = context.getFileSystem().getCompleteFile();
		
		if (_completeFile.isFile()) {
			if (force)
				fileManager.addFileAlways(_completeFile);
			else
				fileManager.addFileIfShared(_completeFile);
		} else if (_completeFile.isDirectory()) {
		    if (force || fileManager.isFileInCompletelySharedDirectory(_completeFile)) {
		        fileManager.addIndividuallySharedFolder(_completeFile);
		    }
		}
		*/
	}

	private void removeFromAzureus() {
		if (_manager != null && _manager.getGlobalManager() != null) {
	        try {
	            _manager.getGlobalManager().removeDownloadManager(_manager);
	        } catch (GlobalManagerDownloadRemovalVetoException e) {
	            e.printStackTrace();
	        }
		}
	}

	/**
	 * @return the next time we should announce to the tracker
	 */
	public long getNextTrackerRequestTime() {
		return _manager != null ? _manager.getTrackerScrapeResponse()
				.getNextScrapeStartTime() : 0;
		//return trackerManager.getNextTrackerRequestTime();
	}

	/**
	 * @return a peer we should try to connect to next
	 */
	public TorrentLocation getTorrentLocation() {
		LOG.debug("getTorrentLocation() invoked, forcing null return value.");
		return null;
		/*
		long now = System.currentTimeMillis();
		TorrentLocation ret = null;
		synchronized (_peers) {
			for (Iterator<TorrentLocation> iter = _peers.iterator(); iter
					.hasNext();) {
				TorrentLocation loc = iter.next();
				if (loc.isBusy(now))
					continue;
				iter.remove();
				if (!linkManager.isConnectedTo(loc)) {
					ret = loc;
					break;
				}
			}
		}
		return ret;
		*/
	}

	/**
	 * trigger a rechoking of the connections
	 */
	private void rechoke() {
		LOG.debug("rechoke() invoked, doing nothing.");
		//choker.rechoke();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.limegroup.bittorrent.Torrent#isPaused()
	 */
	public boolean isPaused() {
		return state.get() == TorrentState.PAUSED;
	}

	/**
	 * two torrents are equal if their infoHashes are.
	 */
	public boolean equals(Object o) {
		if (!(o instanceof ManagedTorrent))
			return false;
		ManagedTorrent mt = (ManagedTorrent) o;

		return Arrays.equals(mt.getInfoHash(), getInfoHash());
	}

	/**
	 * @return if the torrent is active - either downloading or seeding, saving
	 *         or verifying
	 */
	public boolean isActive() {
		synchronized (state.getLock()) {
			if (isDownloading())
				return true;
			switch (state.get()) {
			case SEEDING:
			case VERIFYING:
			case SAVING:
			//case QUEUED:
				return true;
			}
		}
		return false;
	}

	/**
	 * @return if the torrent can be paused
	 */
	public boolean isPausable() {
		synchronized (state.getLock()) {
			if (isDownloading())
				return true;
			switch (state.get()) {
			case VERIFYING:
				return true;
			}
		}
		return false;
	}

	/**
	 * @return if the torrent is currently in one of the downloading states.
	 */
	boolean isDownloading() {
		switch (state.get()) {
		case WAITING_FOR_TRACKER:
		case SCRAPING:
		case CONNECTING:
		case DOWNLOADING:
			return true;
		}
		return false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.limegroup.bittorrent.Torrent#getNumConnections()
	 */
	public int getNumConnections() {
		if (getMeasuredBandwidth(true) > 0 &&
			getNumSeeds() > 0)
			return getNumSeeds();
		
		return 0;
	}
	

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.limegroup.bittorrent.Torrent#getNumPeers()
	 */
	public int getNumPeers() {
		if (_manager == null)
			return 0;

		return _manager.getNbPeers();
		// return _peers.size();
	}
	
	public int getNumSeeds() {
		if (_manager == null)
			return 0;
		
		return _manager.getNbSeeds();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.limegroup.bittorrent.Torrent#getNumBusyPeers()
	 */
	public int getNumNonInterestingPeers() {

		int count = 0;

		if (_manager != null &&
		        _manager.getPeerManager() != null &&
		        _manager.getPeerManager().getPeers() != null) {
			for (PEPeer peer : _manager.getPeerManager().getPeers()) {
				if (!peer.isInteresting())
					count++;
			}
		}

		return count;
		// return linkManager.getNumNonInterestingPeers();
	}

	public int getNumChockingPeers() {

		int count = 0;

		if (_manager != null &&
		        _manager.getPeerManager() != null &&
		        _manager.getPeerManager().getPeers() != null) {
			for (PEPeer peer : _manager.getPeerManager().getPeers()) {
				if (!peer.isChokingMe())
					count++;
			}
		}

		return count;
		// return linkManager.getNumChockingPeers();
	}

	/**
	 * records some data was downloaded
	 */
	public void countDownloaded(int amount) {
		LOG.debug("countDownloaded() invoked... (doing nothing)");
	}

	public long getTotalUploaded() {
		if (_manager == null)
			return 0;
		//System.out.println("ManagedTorrent.getTotalUploaded() -> " + _manager.getStats().getTotalDataBytesSent());
		return _manager.getStats().getTotalDataBytesSent();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.limegroup.bittorrent.Torrent#getTotalDownloaded()
	 */
	public long getTotalDownloaded() {
		if (_manager == null ||
			_manager.getStats()==null) {
			
			if (_hasBeenPaused)
				return _totalDownloaded;
			
			_totalDownloaded=0;
			
			return _totalDownloaded;
		}
		
		if (!isActive())
			return _totalDownloaded;
		//coming back from pause.
		else if (_hasBeenPaused) {
			_totalDownloaded += _manager.getStats().getTotalGoodDataBytesReceived();
			_hasBeenPaused = false;
		} else {
			//find out if we have something written...
			if (_manager.getDiskManager()!=null && _totalDownloaded == 0) {
				_totalDownloaded = _manager.getDiskManager().getTotalLength() - _manager.getDiskManager().getRemaining();
			} else {
				_totalDownloaded = _manager.getStats().getTotalGoodDataBytesReceived();
			}
		}
		
		return _totalDownloaded;
	}

	/**
	 * @return the ratio of uploaded / downloaded data.
	 */
	public float getRatio() {
		//return _info.getRatio();
		if (_manager != null) {
			DownloadManagerStats stats = _manager.getStats();

			return (float) ((float) stats.getDataSendRate()
					/ ((float) stats.getDataReceiveRate() + 0.01));
		} else
			return 0;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.limegroup.bittorrent.Torrent#getAmountLost()
	 */
	public long getAmountLost() {
		if (_manager == null) 
			return 0;
		return _manager.getStats().getDiscarded();		
		//return _folder.getNumCorruptedBytes();
	}

	public boolean hasNonBusyLocations() {
		LOG.debug("hasNonBusyLocations() invoked... (forcing true)");
		return true;
		/*
		long now = System.currentTimeMillis();
		synchronized (_peers) {
			for (TorrentLocation to : _peers) {
				if (!to.isBusy(now))
					return true;
			}
		}
		return false;
		*/
	}

	/**
	 * @return the time until a recently failed location can be retried, or
	 *         Long.MAX_VALUE if no such found.
	 */
	public long getNextLocationRetryTime() {
		LOG.debug("getNextLocationRetryTime() - never.");
		return Long.MAX_VALUE;
		/*
		long soonest = Long.MAX_VALUE;
		long now = System.currentTimeMillis();
		synchronized (_peers) {
			for (TorrentLocation to : _peers) {
				soonest = Math.min(soonest, to.getWaitTime(now));
				if (soonest == 0)
					break;
			}
		}
		return soonest;
		*/
	}

	/**
	 * @return true if continuing is hopeless
	 */
	public boolean shouldStop() {
		//return linkManager.getNumConnections() == 0 && getNumPeers() == 0
		//		&& state.get() != TorrentState.SEEDING;
		
		return getNumConnections() == 0 && getNumPeers() == 0 && state.get() != TorrentState.SEEDING;
	}

	/**
	 * @return the <tt>BTConnectionFetcher</tt> for this torrent.
	 */
	public BTConnectionFetcher getFetcher() {
		LOG.debug("getFetcher() invoked forcing null");
		return null;
		//return _connectionFetcher;
	}

	/**
	 * @return the <tt>SchedulingThreadPool</tt> executing network- related
	 *         tasks
	 */
	public ScheduledExecutorService getNetworkScheduledExecutorService() {
		LOG.debug("getNetworkScheduledExecutorService() invoked forcing null");
		return null;
		//return networkInvoker;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.limegroup.bittorrent.Torrent#measureBandwidth()
	 */
	public void measureBandwidth() {
		//LOG.debug("measureBandwidth() invoked");
		linkManager.measureBandwidth();
		if (_manager == null) {
			//LOG.debug("measureBandwidth() _manager is null");
			return;
		}
		
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.limegroup.bittorrent.Torrent#getMeasuredBandwidth(boolean)
	 */
	public float getMeasuredBandwidth(boolean downstream) {
		if (_manager != null && _manager.getStats() != null) { 
			long dataRate = (downstream) ? _manager.getStats().getDataReceiveRate() :
					_manager.getStats().getDataSendRate();
			
			if (!downstream)
				linkManager.getMeasuredBandwidth(downstream);
			
			return (float) dataRate/ (float) 1024;
		}
	    return 0;
		//return linkManager.getMeasuredBandwidth(downstream);
	}

	public int getTriedHostCount() {
		//LOG.debug("getTriedHostCount() invoked - forcing 5");
		return 5;
		//return _connectionFetcher.getTriedHostCount();
	}

	/**
	 * @return true if this torrent is currently uploading
	 */
	public boolean isUploading() {
		if (_manager == null)
			return false;
		return _manager.getStats().getDataSendRate() > 0;
		//return linkManager.hasUploading();
	}

	/**
	 * @return true if this torrent is currently suspended A torrent is
	 *         considered suspended if there are connections interested in it
	 *         but all are choked.
	 */
	public boolean isSuspended() {
		LOG.debug("isSuspended() invoked. forcing false.");
		return false;
		
		//return isComplete() && linkManager.hasInterested()
		//		&& !linkManager.hasUnchoked();
	}

	public File getTorrentDataFile() {
		return torrentManager.getSharedTorrentMetaDataFile(_info);
	}
	
	public void createTorrentFile() {
		//This uses the folder where the .TORRENTS are saved, not the torrent data.
		_torrentFile = _info.createFileFromRawBytes(SharingSettings.DEFAULT_SHARED_TORRENTS_DIR.getAbsolutePath() + File.separator + _info.getName() + ".torrent");
	}
	
	public void setSaveFile(File saveDirectory, String filename) {
		
		if (_manager == null)
			return;
		
		_changedSaveDir = true;
		try {
			_newSaveDir = saveDirectory.getCanonicalPath() + File.separator + filename;
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}


	public File getTorrentName() {
		return new File(_info.getName());
	}
	
	public void destroy() {
	    try {
	        if (!_hasBeenPaused && !isComplete()) {
                if (_manager != null && _manager.getGlobalManager() != null) {
                    _manager.getGlobalManager().removeDownloadManager(_manager);
                }
            }
        } catch (GlobalManagerDownloadRemovalVetoException e) {
            e.printStackTrace();
        }
	}

    public void removeFromAzureusAndDisk() {
    	removeFromAzureus();
        	
       	if (_manager != null && _manager.getGlobalManager() != null) {	
            // in the future, we must use a better state machine to avoid this hacks
            try {
                File file = _manager.getAbsoluteSaveLocation();
                file.delete();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
