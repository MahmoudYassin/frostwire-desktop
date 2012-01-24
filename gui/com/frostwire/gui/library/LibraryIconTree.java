package com.frostwire.gui.library;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.Enumeration;

import javax.swing.JList;
import javax.swing.JTree;
import javax.swing.ListModel;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;

import com.frostwire.alexandria.Playlist;
import com.frostwire.alexandria.db.LibraryDatabase;
import com.frostwire.gui.library.LibraryFiles.LibraryFilesListCell;
import com.frostwire.gui.library.LibraryPlaylists.LibraryPlaylistsListCell;
import com.frostwire.gui.player.AudioPlayer;
import com.frostwire.gui.player.InternetRadioAudioSource;
import com.frostwire.mplayer.MediaPlaybackState;
import com.limegroup.gnutella.MediaType;
import com.limegroup.gnutella.gui.GUIMediator;

public class LibraryIconTree extends JTree {

    private static final long serialVersionUID = 3025054051505168836L;
    
    private Image speaker;
    private Image loading;

    public LibraryIconTree() {
        loadIcons();
    }

    public LibraryIconTree(TreeModel dataModel) {
        super(dataModel);
        loadIcons();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        AudioPlayer player = AudioPlayer.instance();
        
        if (player.getState() != MediaPlaybackState.Stopped) {
            if (player.getCurrentSong() instanceof InternetRadioAudioSource) {
                TreePath path = getRadioPath();
                if (path != null) {
                    paintIcon(g, speaker, path);
                }
            } else if (player.getCurrentSong() != null && player.getCurrentPlaylist() == null && player.getPlaylistFilesView() != null) {
                TreePath path = getAudioPath();
                if (path != null) {
                    paintIcon(g, speaker, path);
                }
            }
        }
    }

    private void loadIcons() {
        speaker = GUIMediator.getThemeImage("speaker").getImage();
        loading = GUIMediator.getThemeImage("indeterminate_small_progress").getImage();
    }

    private void paintIcon(Graphics g, Image image, TreePath path) {
        Rectangle rect = getUI().getPathBounds(this, path);
        Dimension lsize = rect.getSize();
        Point llocation = rect.getLocation();
        g.drawImage(image, llocation.x + lsize.width - speaker.getWidth(null) - 4, llocation.y + (lsize.height - speaker.getHeight(null)) / 2, null);
    }

    private TreePath getAudioPath() {
        Enumeration<?> e = ((LibraryNode)getModel().getRoot()).depthFirstEnumeration();
        while (e.hasMoreElements()) {
            LibraryNode node = (LibraryNode) e.nextElement();
            if (node instanceof DirectoryHolderNode) {
                DirectoryHolder holder = ((DirectoryHolderNode) node).getDirectoryHolder();
                if (holder instanceof MediaTypeSavedFilesDirectoryHolder
                        && ((MediaTypeSavedFilesDirectoryHolder) holder).getMediaType().equals(MediaType.getAudioMediaType())) {
                    return new TreePath(node.getPath());
                }
            }
        }
        return null;
    }
    
    private TreePath getRadioPath() {
        Enumeration<?> e = ((LibraryNode)getModel().getRoot()).depthFirstEnumeration();
        while (e.hasMoreElements()) {
            LibraryNode node = (LibraryNode) e.nextElement();
            if (node instanceof DirectoryHolderNode) {
                DirectoryHolder holder = ((DirectoryHolderNode) node).getDirectoryHolder();
                if (holder instanceof InternetRadioDirectoryHolder) {
                    return new TreePath(node.getPath());
                }
            }
        }
        return null;
    }
}