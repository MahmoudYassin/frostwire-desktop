package com.frostwire.gui.library;

import java.awt.datatransfer.Transferable;
import java.awt.dnd.InvalidDnDOperationException;
import java.io.File;

import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JTree;
import javax.swing.TransferHandler;
import javax.swing.tree.TreePath;

import org.limewire.util.OSUtils;

import com.frostwire.alexandria.PlaylistItem;
import com.frostwire.gui.player.AudioPlayer;
import com.limegroup.gnutella.MediaType;
import com.limegroup.gnutella.gui.dnd.DNDUtils;

public class LibraryFilesTransferHandler extends TransferHandler {

    private static final long serialVersionUID = -3874985752229848555L;

    private final JTree tree;

    public LibraryFilesTransferHandler(JTree tree) {
        this.tree = tree;
    }

    @Override
    public boolean canImport(TransferSupport support) {

        DropLocation location = support.getDropLocation();
        try {
        TreePath path = tree.getUI().getClosestPathForLocation(tree, location.getDropPoint().x, location.getDropPoint().y);
        if (path != null) {
            LibraryNode node = (LibraryNode)path.getLastPathComponent();
            if (node instanceof DirectoryHolderNode) {
                DirectoryHolder dirHolder = ((DirectoryHolderNode) node).getDirectoryHolder();
                if ((!(dirHolder instanceof MediaTypeSavedFilesDirectoryHolder) || !((MediaTypeSavedFilesDirectoryHolder) dirHolder).getMediaType().equals(MediaType.getAudioMediaType())) && !(dirHolder instanceof StarredDirectoryHolder)) {
                    return false;
                }
            }
        } else {
            return false;
        }
        } catch (Exception e) {
            return false;
        }

        if (support.isDataFlavorSupported(LibraryPlaylistsTableTransferable.ITEM_ARRAY)) {
            return true;
        } else if (DNDUtils.containsFileFlavors(support.getDataFlavors())) {
            if (OSUtils.isMacOSX()) {
                return true;
            }
            try {
                File[] files = DNDUtils.getFiles(support.getTransferable());
                for (File file : files) {
                    if (AudioPlayer.isPlayableFile(file)) {
                        return true;
                    } else if (file.isDirectory()) {
                        if (LibraryUtils.directoryContainsAudio(file)) {
                            return true;
                        }
                    }
                }
                if (files.length == 1 && files[0].getAbsolutePath().endsWith(".m3u")) {
                    return true;
                }
            } catch (InvalidDnDOperationException e) {
                // this case seems to be something special with the OS
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        return false;
    }

    @Override
    public boolean importData(TransferSupport support) {
        if (!canImport(support)) {
            return false;
        }

        try {
            Transferable transferable = support.getTransferable();
            if (DNDUtils.contains(transferable.getTransferDataFlavors(), LibraryPlaylistsTableTransferable.ITEM_ARRAY)) {
                PlaylistItem[] playlistItems = LibraryUtils.convertToPlaylistItems((LibraryPlaylistsTableTransferable.Item[]) transferable.getTransferData(LibraryPlaylistsTableTransferable.ITEM_ARRAY));
                LibraryUtils.createNewPlaylist(playlistItems, isStarredDirectoryHolder(support.getDropLocation()));
            } else {
                File[] files = DNDUtils.getFiles(support.getTransferable());
                if (files.length == 1 && files[0].getAbsolutePath().endsWith(".m3u")) {
                    LibraryUtils.createNewPlaylist(files[0], isStarredDirectoryHolder(support.getDropLocation()));
                } else {
                    LibraryUtils.createNewPlaylist(files, isStarredDirectoryHolder(support.getDropLocation()));
                }
            }
        } catch (Exception e) {
            return false;
        }

        return false;
    }

    @Override
    public int getSourceActions(JComponent c) {
        return COPY;
    }

    private boolean isStarredDirectoryHolder(DropLocation location) {
        TreePath path = tree.getUI().getClosestPathForLocation(tree, location.getDropPoint().x, location.getDropPoint().y);
        if (path != null) {
            LibraryNode node = (LibraryNode)path.getLastPathComponent();
            if (node instanceof DirectoryHolderNode) {
                DirectoryHolder dirHolder = ((DirectoryHolderNode) node).getDirectoryHolder();
                return dirHolder instanceof StarredDirectoryHolder;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }
}
