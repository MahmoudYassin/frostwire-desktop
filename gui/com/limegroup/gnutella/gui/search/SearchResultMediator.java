/*
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.limegroup.gnutella.gui.search;

import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.OverlayLayout;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;

import org.limewire.i18n.I18nMarker;

import com.frostwire.gui.bittorrent.TorrentUtil;
import com.frostwire.gui.filters.TableLineFilter;
import com.frostwire.gui.theme.SkinMenu;
import com.frostwire.gui.theme.SkinMenuItem;
import com.frostwire.gui.theme.SkinPopupMenu;
import com.frostwire.search.torrent.TorrentSearchResult;
import com.limegroup.gnutella.MediaType;
import com.limegroup.gnutella.gui.BoxPanel;
import com.limegroup.gnutella.gui.GUIConstants;
import com.limegroup.gnutella.gui.GUIMediator;
import com.limegroup.gnutella.gui.I18n;
import com.limegroup.gnutella.gui.PaddedPanel;
import com.limegroup.gnutella.gui.actions.SearchAction;
import com.limegroup.gnutella.gui.dnd.DNDUtils;
import com.limegroup.gnutella.gui.dnd.MulticastTransferHandler;
import com.limegroup.gnutella.gui.tables.AbstractTableMediator;
import com.limegroup.gnutella.gui.tables.ActionIconAndNameEditor;
import com.limegroup.gnutella.gui.tables.ColumnPreferenceHandler;
import com.limegroup.gnutella.gui.tables.LimeJTable;
import com.limegroup.gnutella.gui.tables.LimeTableColumn;
import com.limegroup.gnutella.gui.tables.TableSettings;
import com.limegroup.gnutella.gui.util.PopupUtils;
import com.limegroup.gnutella.settings.SearchSettings;
import com.limegroup.gnutella.util.QueryUtils;

public class SearchResultMediator extends AbstractTableMediator<TableRowFilteredModel, SearchResultDataLine, UISearchResult> {

    protected static final String SEARCH_TABLE = "SEARCH_TABLE";

    private static final DateRenderer DATE_RENDERER = new DateRenderer();
    private static final PercentageRenderer PERCENTAGE_RENDERER = new PercentageRenderer();
    private static final SearchResultNameRenderer SEARCH_RESULT_NAME_RENDERER = new SearchResultNameRenderer();

    /**
     * The TableSettings that all ResultPanels will use.
     */
    static final TableSettings SEARCH_SETTINGS = new TableSettings("SEARCH_TABLE");

    /**
     * The search info of this class.
     */
    private final SearchInformation SEARCH_INFO;

    /**
     * The search token of the last search. (Use this to match up results.)
     */
    private long token;

    private final List<String> searchTokens;

    /**
     * The CompositeFilter for this ResultPanel.
     */
    CompositeFilter FILTER;

    /**
     * The download listener.
     */
    ActionListener DOWNLOAD_LISTENER;

    /**
     * The browse host listener.
     */

    MouseAdapter TORRENT_DETAILS_LISTENER;

    private ActionListener COPY_MAGNET_ACTION_LISTENER;

    private ActionListener COPY_HASH_ACTION_LISTENER;

    ActionListener CONFIGURE_SHARING_LISTENER;

    ActionListener DOWNLOAD_PARTIAL_FILES_LISTENER;

    ActionListener STOP_SEARCH_LISTENER;

    protected Box SOUTH_PANEL;

    public AtomicInteger searchCount = new AtomicInteger(0);

    /**
     * Specialized constructor for creating a "dummy" result panel.
     * This should only be called once at search window creation-time.
     */
    SearchResultMediator(JPanel overlay) {
        super(SEARCH_TABLE);
        setupFakeTable(overlay);

        SEARCH_INFO = SearchInformation.createKeywordSearch("", null, MediaType.getAnyTypeMediaType());
        FILTER = null;
        this.token = 0;
        this.searchTokens = null;
        setButtonEnabled(SearchButtons.TORRENT_DETAILS_BUTTON_INDEX, false);
        // disable dnd for overlay panel
        TABLE.setDragEnabled(false);
        TABLE.setTransferHandler(null);

        SOUTH_PANEL.setVisible(false);
    }

    /**
     * Constructs a new ResultPanel for search results.
     *
     * @param guid the guid of the query.  Used to match results.
     * @param info the info of the search
     */
    SearchResultMediator(long token, List<String> searchTokens, SearchInformation info) {
        super(SEARCH_TABLE);
        SEARCH_INFO = info;
        this.token = token;
        this.searchTokens = searchTokens;
        setupRealTable();
        resetFilters();
    }

    /**
     * Sets the default renderers to be used in the table.
     */
    protected void setDefaultRenderers() {
        super.setDefaultRenderers();
        TABLE.setDefaultRenderer(Date.class, DATE_RENDERER);
        TABLE.setDefaultRenderer(Float.class, PERCENTAGE_RENDERER);
        TABLE.setDefaultRenderer(SearchResultNameHolder.class, SEARCH_RESULT_NAME_RENDERER);
    }

    /**
     * Does nothing.
     */
    protected void updateSplashScreen() {
    }

    /**
     * Setup the data model 
     */
    protected void setupDataModel() {
        DATA_MODEL = new TableRowFilteredModel(FILTER);
    }

    /**
     * Sets up the constants:
     * FILTER, MAIN_PANEL, DATA_MODEL, TABLE, BUTTON_ROW.
     */
    protected void setupConstants() {

        FILTER = new CompositeFilter(4);
        MAIN_PANEL = new PaddedPanel();

        setupDataModel();

        TABLE = new LimeJTable(DATA_MODEL);

        BUTTON_ROW = new SearchButtons(this).getComponent();
    }

    @Override
    protected void setupDragAndDrop() {
        TABLE.setDragEnabled(true);
        TABLE.setTransferHandler(new MulticastTransferHandler(new ResultPanelTransferHandler(this), DNDUtils.DEFAULT_TRANSFER_HANDLERS));
    }

    /**
     * Sets SETTINGS to be the static SEARCH_SETTINGS, instead
     * of constructing a new one for each ResultPanel.
     */
    protected void buildSettings() {
        SETTINGS = SEARCH_SETTINGS;
    }

    /**
     * Creates the specialized SearchColumnSelectionMenu menu,
     * which groups XML columns together.
     */
    protected JPopupMenu createColumnSelectionMenu() {
        return (new SearchColumnSelectionMenu(TABLE)).getComponent();
    }

    /**
     * Creates the specialized column preference handler for search columns.
     */
    protected ColumnPreferenceHandler createDefaultColumnPreferencesHandler() {
        return new SearchColumnPreferenceHandler(TABLE);
    }

    @Override
    protected void addListeners() {
        super.addListeners();
    }

    /** Sets all the listeners. */
    protected void buildListeners() {
        super.buildListeners();

        DOWNLOAD_LISTENER = new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                SearchMediator.doDownload(SearchResultMediator.this);
            }
        };

        TORRENT_DETAILS_LISTENER = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) {
                    final SearchResultDataLine[] lines = getAllSelectedLines();
                    if (lines.length == 1) {
                        UISearchResult searchResult = lines[0].getSearchResult();
                        searchResult.showDetails(true);
                    }
                }
            }
        };

        COPY_MAGNET_ACTION_LISTENER = new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                SearchResultDataLine[] lines = getAllSelectedLines();
                String str = "";
                for (SearchResultDataLine line : lines) {
                    str += TorrentUtil.getMagnet(line.getInitializeObject().getHash());
                    str += "\n";
                }
                GUIMediator.setClipboardContent(str);
            }
        };

        COPY_HASH_ACTION_LISTENER = new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                SearchResultDataLine[] lines = getAllSelectedLines();
                String str = "";
                for (SearchResultDataLine line : lines) {
                    str += line.getInitializeObject().getHash();
                    str += "\n";
                }
                GUIMediator.setClipboardContent(str);
            }
        };

        CONFIGURE_SHARING_LISTENER = new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                GUIMediator.instance().setOptionsVisible(true, I18n.tr("Options"));
            }
        };

        DOWNLOAD_PARTIAL_FILES_LISTENER = new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                SearchResultDataLine[] lines = getAllSelectedLines();
                if (lines.length == 1 && lines[0] != null) {
                    if (lines[0].getInitializeObject().getSearchResult() instanceof TorrentSearchResult) {
                        GUIMediator.instance().openTorrentSearchResult((TorrentSearchResult) lines[0].getInitializeObject().getSearchResult(), true);
                    }
                }
            }
        };

        STOP_SEARCH_LISTENER = new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                SearchMediator.instance().stopSearch(token);
                updateSearchIcon(false);
                setButtonEnabled(SearchButtons.STOP_SEARCH_BUTTON_INDEX, false);
            }
        };
    }

    /**
     * Creates the specialized SearchResultMenu for right-click popups.
     *
     * Upgraded access from protected to public for SearchResultDisplayer.
     */
    public JPopupMenu createPopupMenu() {
        return createPopupMenu(getAllSelectedLines());
    }

    protected JPopupMenu createPopupMenu(SearchResultDataLine[] lines) {
        //  do not return a menu if right-clicking on the dummy panel
        if (!isKillable())
            return null;

        JPopupMenu menu = new SkinPopupMenu();

        if (lines.length > 0) {
            boolean allWithHash = true;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].getHash() == null) {
                    allWithHash = false;
                    break;
                }
            }
            PopupUtils.addMenuItem(I18n.tr("Copy Magnet"), COPY_MAGNET_ACTION_LISTENER, menu, !isStopped() && allWithHash);
            PopupUtils.addMenuItem(I18n.tr("Copy Hash"), COPY_HASH_ACTION_LISTENER, menu, !isStopped() && allWithHash);

            menu.add(createSearchAgainMenu(lines[0]));
        } else {
            menu.add(new SkinMenuItem(new RepeatSearchAction()));
        }

        return (new SearchResultMenu(this)).addToMenu(menu, lines);
    }

    /**
     * Returns a menu with a 'repeat search' and 'repeat search no clear' action.
     */
    protected final JMenu createSearchAgainMenu(SearchResultDataLine line) {
        JMenu menu = new SkinMenu(I18n.tr("Search More"));
        menu.add(new SkinMenuItem(new RepeatSearchAction()));

        if (line == null) {
            menu.setEnabled(isRepeatSearchEnabled());
            return menu;
        }

        menu.addSeparator();
        String keywords = QueryUtils.createQueryString(line.getFilename());
        SearchInformation info = SearchInformation.createKeywordSearch(keywords, null, MediaType.getAnyTypeMediaType());
        if (SearchMediator.validateInfo(info) == SearchMediator.QUERY_VALID) {
            menu.add(new SkinMenuItem(new SearchAction(info, I18nMarker.marktr("Search for Keywords: {0}"))));
        }

        return menu;
    }

    /**
     * Do not allow removal of rows.
     */
    public void removeSelection() {
    }

    /**
     * Clears the table and converts the download button into a
     * wishlist button.
     */
    public void clearTable() {
        super.clearTable();
    }

    /**
     * Sets the appropriate buttons to be disabled.
     */
    public void handleNoSelection() {
        setButtonEnabled(SearchButtons.DOWNLOAD_BUTTON_INDEX, false);
        setButtonEnabled(SearchButtons.TORRENT_DETAILS_BUTTON_INDEX, false);
        setButtonEnabled(SearchButtons.STOP_SEARCH_BUTTON_INDEX, !isStopped());
    }

    /**
     * Sets the appropriate buttons to be enabled.
     */
    public void handleSelection(int i) {
        setButtonEnabled(SearchButtons.DOWNLOAD_BUTTON_INDEX, true);

        setButtonEnabled(SearchButtons.STOP_SEARCH_BUTTON_INDEX, !isStopped());

        // Buy button only enabled for single selection.
        SearchResultDataLine[] allSelectedLines = getAllSelectedLines();
        setButtonEnabled(SearchButtons.TORRENT_DETAILS_BUTTON_INDEX, allSelectedLines != null && allSelectedLines.length == 1);
    }

    /**
     * Forwards the event to DOWNLOAD_LISTENER.
     */
    public void handleActionKey() {
        DOWNLOAD_LISTENER.actionPerformed(null);
    }

    /**
     * Gets the SearchInformation of this search.
     */
    SearchInformation getSearchInformation() {
        return SEARCH_INFO;
    }

    /**
     * Gets the query of the search.
     */
    String getQuery() {
        return SEARCH_INFO.getQuery();
    }

    /**
     * Returns the title of the search.
     * @return
     */
    String getTitle() {
        return SEARCH_INFO.getTitle();
    }

    /**
     * Gets the rich query of the search.
     */
    String getRichQuery() {
        return SEARCH_INFO.getXML();
    }

    /**
     * Shows a LicenseWindow for the selected line.
     */
    void showLicense() {
        //        TableLine line = getSelectedLine();
        //        if(line == null)
        //            return;
        //            
        //        URN urn = line.getSHA1Urn();
        //        LimeXMLDocument doc = line.getXMLDocument();
        //        LicenseWindow window = LicenseWindow.create(line.getLicense(), urn, doc, this);
        //        GUIUtils.centerOnScreen(window);
        //        window.setVisible(true);
    }

    /**
     * Determines whether or not this panel is stopped.
     */
    boolean isStopped() {
        return token == 0;
    }

    /**
     * Determines if this is empty.
     */
    boolean isEmpty() {
        return DATA_MODEL.getRowCount() == 0;
    }

    /**
     * Determines if this can be removed.
     */
    boolean isKillable() {
        // the dummy panel has a null filter, and is the only one not killable
        return FILTER != null;
    }

    /**
     * Notification that a filter on this panel has changed.
     *
     * Updates the data model with the new list, maintains the selection,
     * and moves the viewport to the first still visible selected row.
     *
     * Note that the viewport moving cannot be done by just storing the first
     * visible row, because after the filters change, the row might not exist
     * anymore.  Thus, it is necessary to store all visible rows and move to
     * the first still-visible one.
     */
    boolean filterChanged(TableLineFilter<SearchResultDataLine> filter, int depth) {
        FILTER.setFilter(depth, filter);
        //if(!FILTER.setFilter(depth, filter))
        //    return false;

        // store the selection & visible rows
        int[] rows = TABLE.getSelectedRows();
        SearchResultDataLine[] lines = new SearchResultDataLine[rows.length];
        List<SearchResultDataLine> inView = new LinkedList<SearchResultDataLine>();
        for (int i = 0; i < rows.length; i++) {
            int row = rows[i];
            SearchResultDataLine line = DATA_MODEL.get(row);
            lines[i] = line;
            if (TABLE.isRowVisible(row))
                inView.add(line);
        }

        // change the table.
        DATA_MODEL.filtersChanged();

        // reselect & move the viewpoint to the first still visible row.
        for (int i = 0; i < rows.length; i++) {
            SearchResultDataLine line = lines[i];
            int row = DATA_MODEL.getRow(line);
            if (row != -1) {
                TABLE.addRowSelectionInterval(row, row);
                if (inView != null && inView.contains(line)) {
                    TABLE.ensureRowVisible(row);
                    inView = null;
                }
            }
        }

        // update the tab count.
        SearchMediator.setTabDisplayCount(this);
        return true;
    }

    int totalResults() {
        return ((ResultPanelModel) DATA_MODEL).getTotalResults();
    }

    int filteredResults() {
        return DATA_MODEL.getFilteredResults();
    }

    /**
     * Determines whether or not repeat search is currently enabled.
     * Repeat search will be disabled if, for example, the original
     * search was performed too recently.
     *
     * @return <tt>true</tt> if the repeat search feature is currently
     *  enabled, otherwise <tt>false</tt>
     */
    boolean isRepeatSearchEnabled() {
        return FILTER != null;
    }

    void repeatSearch() {
        clearTable();
        resetFilters();

        SearchMediator.setTabDisplayCount(this);
        SearchMediator.instance().repeatSearch(this, SEARCH_INFO);
        setButtonEnabled(SearchButtons.TORRENT_DETAILS_BUTTON_INDEX, false);
        setButtonEnabled(SearchButtons.STOP_SEARCH_BUTTON_INDEX, !isStopped());
    }

    void resetFilters() {
        FILTER.reset();
        DATA_MODEL.setJunkFilter(null);
    }

    /** Returns true if this is responsible for results with the given GUID */
    boolean matches(long token) {
        return this.token == token;
    }

    void setToken(long token) {
        this.token = token;
    }

    /** Returns the search token this is responsible for. */
    long getToken() {
        return token;
    }

    /** Returns the media type this is responsible for. */
    MediaType getMediaType() {
        return SEARCH_INFO.getMediaType();
    }

    /**
     * Gets all currently selected TableLines.
     * 
     * @return empty array if no lines are selected.
     */
    SearchResultDataLine[] getAllSelectedLines() {
        int[] rows = TABLE.getSelectedRows();
        if (rows == null)
            return new SearchResultDataLine[0];

        SearchResultDataLine[] lines = new SearchResultDataLine[rows.length];
        for (int i = 0; i < rows.length; i++)
            lines[i] = DATA_MODEL.get(rows[i]);
        return lines;
    }

    /**
     * Gets the currently selected TableLine.
     * 
     * @return null if there is no selected line.
     */
    SearchResultDataLine getSelectedLine() {
        int selected = TABLE.getSelectedRow();
        if (selected != -1)
            return DATA_MODEL.get(selected);
        else
            return null;
    }

    /**
     * Gets the TableLine at <code>index</code>
     * 
     * @param index index of the line you want
     * @return null if there is no selected line.
     */
    final SearchResultDataLine getLine(int index) {
        return DATA_MODEL.get(index);
    }

    /**
     * Sets extra values for non dummy ResultPanels.
     * (Used for all tables that will have results.)
     *
     * Currently:
     * - Sorts the count column, if it is visible & real-time sorting is on.
     * - Adds listeners, so the filters can be displayed when necessary.
     */
    private void setupRealTable() {
        SearchTableColumns columns = ((ResultPanelModel) DATA_MODEL).getColumns();
        LimeTableColumn countColumn = columns.getColumn(SearchTableColumns.COUNT_IDX);
        if (SETTINGS.REAL_TIME_SORT.getValue() && TABLE.isColumnVisible(countColumn.getId())) {
            DATA_MODEL.sort(SearchTableColumns.COUNT_IDX); // ascending
            DATA_MODEL.sort(SearchTableColumns.COUNT_IDX); // descending
        }

        MouseListener filterDisplayer = new MouseListener() {
            public void mouseClicked(MouseEvent e) {
                if (e.isConsumed())
                    return;
                e.consume();
            }

            public void mousePressed(MouseEvent e) {
            }

            public void mouseReleased(MouseEvent e) {
            }

            public void mouseEntered(MouseEvent e) {
            }

            public void mouseExited(MouseEvent e) {
            }
        };
        // catches around the button area.
        MAIN_PANEL.addMouseListener(filterDisplayer);
        // catches the blank area before results fill in
        SCROLL_PANE.addMouseListener(filterDisplayer);
        // catches selections on the table
        TABLE.addMouseListener(filterDisplayer);
        // catches the table header
        TABLE.getTableHeader().addMouseListener(filterDisplayer);
    }

    protected void setupMainPanelBase() {
        if (SearchSettings.ENABLE_SPAM_FILTER.getValue() && MAIN_PANEL != null) {
            MAIN_PANEL.add(getScrolledTablePane());
            addButtonRow();
            MAIN_PANEL.setMinimumSize(ZERO_DIMENSION);
        } else {
            super.setupMainPanel();
        }
    }

    /**
     * Overwritten
     */
    protected void setupMainPanel() {
        //MAIN_PANEL.add(createSecurityWarning()); //No warnings

        setupMainPanelBase();
    }

    /**
     * Adds the overlay panel into the table & converts the button
     * to 'download'.
     */
    private void setupFakeTable(JPanel overlay) {
        MAIN_PANEL.removeAll();

        //Fixes flickering!
        JPanel background = new JPanel() {
            private static final long serialVersionUID = 8931395134232576566L;

            public boolean isOptimizedDrawingEnabled() {
                return false;
            }
        };

        background.setLayout(new OverlayLayout(background));

        JPanel overlayPanel = new BoxPanel(BoxPanel.Y_AXIS);
        overlayPanel.setOpaque(false);
        overlayPanel.add(Box.createVerticalStrut(20));
        overlayPanel.add(overlay);

        overlayPanel.setMinimumSize(new Dimension(0, 0));
        JComponent table = getScrolledTablePane();
        table.setOpaque(false);
        background.add(overlayPanel);
        background.add(table);

        MAIN_PANEL.add(background);
        addButtonRow();

        MAIN_PANEL.setMinimumSize(ZERO_DIMENSION);
    }

    /**
     * Adds the button row and the Spam Button
     */
    private void addButtonRow() {
        if (BUTTON_ROW != null) {
            SOUTH_PANEL = Box.createVerticalBox();
            SOUTH_PANEL.setOpaque(false);

            SOUTH_PANEL.add(Box.createVerticalStrut(GUIConstants.SEPARATOR));

            JPanel buttonPanel = new JPanel();
            buttonPanel.setLayout(new GridBagLayout());

            GridBagConstraints gbc = new GridBagConstraints();

            gbc.gridx = 1;
            gbc.gridy = 0;
            gbc.anchor = GridBagConstraints.CENTER;
            gbc.fill = GridBagConstraints.NONE;
            gbc.gridwidth = GridBagConstraints.RELATIVE;
            gbc.weightx = 1;

            buttonPanel.add(BUTTON_ROW, gbc);

            buttonPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));
            SOUTH_PANEL.add(buttonPanel);

            MAIN_PANEL.add(SOUTH_PANEL);
        }
    }

    public void cleanup() {
    }

    protected void setDefaultEditors() {
        TableColumnModel model = TABLE.getColumnModel();
        TableColumn tc;
        tc = model.getColumn(SearchTableColumns.NAME_IDX);
        tc.setCellEditor(new SearchResultNameEditor());
        tc = model.getColumn(SearchTableColumns.SOURCE_IDX);
        tc.setCellEditor(new ActionIconAndNameEditor());
    }

    private final class RepeatSearchAction extends AbstractAction {

        /**
         * 
         */
        private static final long serialVersionUID = -209446182720400951L;

        public RepeatSearchAction() {
            putValue(Action.NAME, SearchMediator.REPEAT_SEARCH_STRING);
            setEnabled(isRepeatSearchEnabled());
        }

        public void actionPerformed(ActionEvent e) {
            repeatSearch();
        }
    }

    void updateSearchIcon(boolean active) {
        SearchMediator.getSearchResultDisplayer().updateSearchIcon(this, active);
        setButtonEnabled(SearchButtons.STOP_SEARCH_BUTTON_INDEX, active);
    }

    List<String> getSearchTokens() {
        return searchTokens;
    }
}
