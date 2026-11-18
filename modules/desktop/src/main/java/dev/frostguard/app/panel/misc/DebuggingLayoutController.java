package dev.frostguard.app.panel.misc;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import javax.imageio.ImageIO;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.service.ProfileService;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import dev.frostguard.vision.ocr.OcrEngine;

import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;

public class DebuggingLayoutController {

    private static final double BASE_VIEW_WIDTH = 316.0;
    private static final double BASE_VIEW_HEIGHT = 576.0;
    private static final double BASE_CONTAINER_WIDTH = 320.0;
    private static final double BASE_CONTAINER_HEIGHT = 580.0;
    private static final double MIN_ZOOM = 1.0;
    private static final double MAX_ZOOM = 4.0;
    private static final double ZOOM_STEP = 0.1;
    private static final double POINT_DRAG_THRESHOLD = 4.0;

    @FXML
    private Rectangle selectionBox;

    @FXML
    private ScrollPane previewScrollPane;

    @FXML
    private StackPane imageContainer;

    @FXML
    private ImageView screenshotImageView;

    @FXML
    private Label dragDropHint;

    @FXML
    private Pane crosshairPane;

    @FXML
    private Line crosshairX;

    @FXML
    private Line crosshairY;

    @FXML
    private Label coordinatesLabel;

    @FXML
    private Label zoomLabel;

    @FXML
    private TextField searchTextField;

    @FXML
    private ListView<File> templateListView;

    @FXML
    private TextField templateNameField;

    @FXML
    private ComboBox<String> templateFolderCombo;

    @FXML
    private ComboBox<String> actionComboBox;

    @FXML
    private Button btnRunAction;

    @FXML
    private TextArea logTextArea;

    @FXML
    private ComboBox<AccountDescriptor> profileComboBox;

    @FXML
    private Button btnCaptureAdb;

    private final Rectangle imageClip = new Rectangle(BASE_VIEW_WIDTH, BASE_VIEW_HEIGHT);

    private double dragStartX = -1;
    private double dragStartY = -1;
    private boolean isDragging = false;
    private boolean isCrosshairLocked = false;
    private double zoom = 1.0;

    private File currentScreenshotFile;
    private File templateRootDir;
    private List<File> allTemplates = new ArrayList<>();

    private record ViewSize(double width, double height) {}

    private record ImagePoint(int x, int y) {
        String asPlain() {
            return x + "," + y;
        }
    }

    private record ImageRect(int x1, int y1, int x2, int y2) {
        int width() {
            return x2 - x1;
        }

        int height() {
            return y2 - y1;
        }

        String asPlain() {
            return x1 + "," + y1 + " - " + x2 + "," + y2;
        }

        boolean isValid() {
            return width() > 0 && height() > 0;
        }
    }

    @FXML
    public void initialize() {
        imageClip.setArcWidth(30);
        imageClip.setArcHeight(30);
        screenshotImageView.setClip(imageClip);

        applyZoomLayout(false);
        setupDragAndDrop();
        setupProfiles();
        setupZoomHotkeys();
        loadTemplates();
        setupSearchFilter();
        setupActions();
    }

    private ViewSize currentViewSize() {
        return new ViewSize(screenshotImageView.getFitWidth(), screenshotImageView.getFitHeight());
    }

    private ImagePoint viewToImage(double containerX, double containerY) {
        Image img = screenshotImageView.getImage();
        if (img == null) {
            return null;
        }

        ViewSize view = currentViewSize();
        double offsetX = (imageContainer.getWidth() - view.width()) / 2.0;
        double offsetY = (imageContainer.getHeight() - view.height()) / 2.0;

        double relX = containerX - offsetX;
        double relY = containerY - offsetY;

        if (relX < 0 || relX > view.width() || relY < 0 || relY > view.height()) {
            return null;
        }

        int imgX = (int) ((relX / view.width()) * img.getWidth());
        int imgY = (int) ((relY / view.height()) * img.getHeight());
        imgX = Math.max(0, Math.min(imgX, (int) img.getWidth() - 1));
        imgY = Math.max(0, Math.min(imgY, (int) img.getHeight() - 1));
        return new ImagePoint(imgX, imgY);
    }

    private ImageRect selectionToImageRect() {
        if (selectionBox.getWidth() <= 0 || selectionBox.getHeight() <= 0 || !selectionBox.isVisible()) {
            return null;
        }
        Image img = screenshotImageView.getImage();
        if (img == null) {
            return null;
        }

        ViewSize view = currentViewSize();
        double offsetX = (imageContainer.getWidth() - view.width()) / 2.0;
        double offsetY = (imageContainer.getHeight() - view.height()) / 2.0;

        double relStartX = selectionBox.getX() - offsetX;
        double relStartY = selectionBox.getY() - offsetY;
        double relEndX = (selectionBox.getX() + selectionBox.getWidth()) - offsetX;
        double relEndY = (selectionBox.getY() + selectionBox.getHeight()) - offsetY;

        int imgStartX = (int) ((relStartX / view.width()) * img.getWidth());
        int imgStartY = (int) ((relStartY / view.height()) * img.getHeight());
        int imgEndX = (int) ((relEndX / view.width()) * img.getWidth());
        int imgEndY = (int) ((relEndY / view.height()) * img.getHeight());

        imgStartX = Math.max(0, Math.min(imgStartX, (int) img.getWidth() - 1));
        imgStartY = Math.max(0, Math.min(imgStartY, (int) img.getHeight() - 1));
        imgEndX = Math.max(0, Math.min(imgEndX, (int) img.getWidth() - 1));
        imgEndY = Math.max(0, Math.min(imgEndY, (int) img.getHeight() - 1));

        int x1 = Math.min(imgStartX, imgEndX);
        int y1 = Math.min(imgStartY, imgEndY);
        int x2 = Math.max(imgStartX, imgEndX);
        int y2 = Math.max(imgStartY, imgEndY);
        return new ImageRect(x1, y1, x2, y2);
    }

    private void copyPlain(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void setupZoomHotkeys() {
        imageContainer.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                newScene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
                    if (event.isShortcutDown() && event.getCode() == KeyCode.DIGIT0) {
                        resetZoom();
                        event.consume();
                    }
                });
            }
        });
    }

    @FXML
    private void handlePreviewScroll(ScrollEvent event) {
        if (!event.isControlDown()) {
            return;
        }
        if (screenshotImageView.getImage() == null) {
            event.consume();
            return;
        }

        double oldZoom = zoom;
        double delta = event.getDeltaY() > 0 ? ZOOM_STEP : -ZOOM_STEP;
        double newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom + delta));
        if (Math.abs(newZoom - oldZoom) < 0.0001) {
            event.consume();
            return;
        }

        // Cursor position relative to the scroll viewport, for zoom-toward-cursor.
        Bounds viewportBounds = previewScrollPane.getViewportBounds();
        Point2D cursorInContent = imageContainer.sceneToLocal(event.getSceneX(), event.getSceneY());
        double contentX = cursorInContent.getX();
        double contentY = cursorInContent.getY();

        zoom = newZoom;
        applyZoomLayout(true);

        double contentWidth = imageContainer.getPrefWidth();
        double contentHeight = imageContainer.getPrefHeight();
        double viewW = viewportBounds.getWidth();
        double viewH = viewportBounds.getHeight();

        if (contentWidth > viewW) {
            double targetH = (contentX * (zoom / oldZoom) - event.getX()) / (contentWidth - viewW);
            previewScrollPane.setHvalue(clamp01(targetH));
        }
        if (contentHeight > viewH) {
            double targetV = (contentY * (zoom / oldZoom) - event.getY()) / (contentHeight - viewH);
            previewScrollPane.setVvalue(clamp01(targetV));
        }

        event.consume();
    }

    @FXML
    private void handleMouseClicked(MouseEvent event) {
        if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
            resetZoom();
            event.consume();
        }
    }

    private void resetZoom() {
        zoom = 1.0;
        applyZoomLayout(true);
        previewScrollPane.setHvalue(0);
        previewScrollPane.setVvalue(0);
        log("Zoom reset to 1.0x");
    }

    private void applyZoomLayout(boolean logChange) {
        double viewW = BASE_VIEW_WIDTH * zoom;
        double viewH = BASE_VIEW_HEIGHT * zoom;
        double containerW = BASE_CONTAINER_WIDTH * zoom;
        double containerH = BASE_CONTAINER_HEIGHT * zoom;

        screenshotImageView.setFitWidth(viewW);
        screenshotImageView.setFitHeight(viewH);
        imageClip.setWidth(viewW);
        imageClip.setHeight(viewH);

        imageContainer.setPrefSize(containerW, containerH);
        imageContainer.setMinSize(containerW, containerH);
        imageContainer.setMaxSize(containerW, containerH);
        crosshairPane.setPrefSize(containerW, containerH);

        if (zoomLabel != null) {
            zoomLabel.setText(String.format("Zoom %.1fx  (Ctrl+scroll, double-click reset)", zoom));
        }
        if (logChange) {
            log(String.format("Zoom: %.1fx", zoom));
        }
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    @FXML
    private void handleMouseMoved(MouseEvent event) {
        if (currentScreenshotFile == null || screenshotImageView.getImage() == null) {
            return;
        }

        if (isCrosshairLocked) {
            isCrosshairLocked = false;
        }

        ImagePoint point = viewToImage(event.getX(), event.getY());
        if (point == null) {
            crosshairPane.setVisible(false);
            return;
        }

        showCrosshairAtContainer(event.getX(), event.getY(), point.x(), point.y());
    }

    private void showCrosshairAtContainer(double containerX, double containerY, int imgX, int imgY) {
        crosshairX.setStartX(0);
        crosshairX.setEndX(imageContainer.getWidth());
        crosshairX.setStartY(containerY);
        crosshairX.setEndY(containerY);

        crosshairY.setStartX(containerX);
        crosshairY.setEndX(containerX);
        crosshairY.setStartY(0);
        crosshairY.setEndY(imageContainer.getHeight());

        coordinatesLabel.setText(String.format("X: %d\nY: %d", imgX, imgY));

        double labelX = containerX + 15;
        double labelY = containerY + 15;
        if (labelX + coordinatesLabel.getWidth() > imageContainer.getWidth()) {
            labelX = containerX - 15 - coordinatesLabel.getWidth();
        }
        if (labelY + coordinatesLabel.getHeight() > imageContainer.getHeight()) {
            labelY = containerY - 15 - coordinatesLabel.getHeight();
        }
        coordinatesLabel.setLayoutX(labelX);
        coordinatesLabel.setLayoutY(labelY);

        if (!isDragging) {
            crosshairPane.setVisible(true);
            crosshairX.setVisible(true);
            crosshairY.setVisible(true);
            coordinatesLabel.setVisible(true);
        }
    }

    @FXML
    private void handleMousePressed(MouseEvent event) {
        if (currentScreenshotFile == null || screenshotImageView.getImage() == null) {
            return;
        }

        dragStartX = event.getX();
        dragStartY = event.getY();
        isDragging = true;

        selectionBox.setX(dragStartX);
        selectionBox.setY(dragStartY);
        selectionBox.setWidth(0);
        selectionBox.setHeight(0);
        selectionBox.setVisible(true);

        crosshairX.setVisible(false);
        crosshairY.setVisible(false);
        coordinatesLabel.setVisible(false);
    }

    @FXML
    private void handleMouseDragged(MouseEvent event) {
        if (!isDragging || currentScreenshotFile == null) {
            return;
        }

        double currentX = Math.max(0, Math.min(event.getX(), imageContainer.getWidth()));
        double currentY = Math.max(0, Math.min(event.getY(), imageContainer.getHeight()));

        double x = Math.min(dragStartX, currentX);
        double y = Math.min(dragStartY, currentY);
        double width = Math.abs(currentX - dragStartX);
        double height = Math.abs(currentY - dragStartY);

        selectionBox.setX(x);
        selectionBox.setY(y);
        selectionBox.setWidth(width);
        selectionBox.setHeight(height);

        if (!crosshairPane.isVisible()) {
            crosshairPane.setVisible(true);
        }
    }

    @FXML
    private void handleMouseReleased(MouseEvent event) {
        if (!isDragging) {
            return;
        }
        isDragging = false;

        String action = actionComboBox.getValue();
        if ("OCR".equals(action)) {
            performOCROnSelection();
        } else if ("Copy Point".equals(action)) {
            copyPointFromInteraction(event.getX(), event.getY());
        } else if ("Copy Region".equals(action)) {
            copyRegionFromSelection();
        } else if ("Save Template".equals(action)) {
            String name = templateNameField != null ? templateNameField.getText() : null;
            if (name != null && !name.trim().isEmpty()) {
                saveTemplateFromSelection();
            }
        }
    }

    @FXML
    private void handleMouseEntered(MouseEvent event) {
        if (currentScreenshotFile != null && !isCrosshairLocked) {
            crosshairPane.setVisible(true);
        }
    }

    @FXML
    private void handleMouseExited(MouseEvent event) {
        if (crosshairPane != null && !isCrosshairLocked) {
            crosshairPane.setVisible(false);
        }
    }

    private void setupSearchFilter() {
        if (searchTextField != null) {
            searchTextField.textProperty().addListener((observable, oldValue, newValue) -> {
                if (newValue == null || newValue.trim().isEmpty()) {
                    templateListView.getItems().setAll(allTemplates);
                } else {
                    String lowerCaseFilter = newValue.toLowerCase();
                    List<File> filteredList = new ArrayList<>();
                    for (File file : allTemplates) {
                        if (file.getName().toLowerCase().contains(lowerCaseFilter)) {
                            filteredList.add(file);
                        }
                    }
                    templateListView.getItems().setAll(filteredList);
                }
            });
        }
    }

    private void setupDragAndDrop() {
        imageContainer.setOnDragOver(event -> {
            Dragboard db = event.getDragboard();
            if (db.hasFiles()) {
                boolean isImage = db.getFiles().stream().anyMatch(file -> {
                    String name = file.getName().toLowerCase();
                    return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                            || name.endsWith(".bmp");
                });
                if (isImage) {
                    event.acceptTransferModes(TransferMode.COPY);
                    imageContainer.getStyleClass().add("drag-hover");
                }
            }
            event.consume();
        });

        imageContainer.setOnDragExited(event -> {
            imageContainer.getStyleClass().remove("drag-hover");
            event.consume();
        });

        imageContainer.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;

            imageContainer.getStyleClass().remove("drag-hover");

            if (db.hasFiles()) {
                for (File file : db.getFiles()) {
                    String name = file.getName().toLowerCase();
                    if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                            || name.endsWith(".bmp")) {
                        loadScreenshot(file);
                        success = true;
                        break;
                    }
                }
            }
            event.setDropCompleted(success);
            event.consume();
        });

        imageContainer.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                newScene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
                    if (event.isShortcutDown() && event.getCode() == KeyCode.V) {
                        Clipboard clipboard = Clipboard.getSystemClipboard();
                        if (clipboard.hasImage()) {
                            Image image = clipboard.getImage();
                            screenshotImageView.setImage(image);
                            dragDropHint.setVisible(false);
                            try {
                                File tempFile = File.createTempFile("clipboard_screenshot", ".png");
                                tempFile.deleteOnExit();
                                ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(image, null), "png", tempFile);
                                currentScreenshotFile = tempFile;
                                log("Loaded screenshot from clipboard");
                                log("Size: " + image.getWidth() + "x" + image.getHeight());
                            } catch (Exception e) {
                                log("Error saving clipboard image to temp file: " + e.getMessage());
                            }
                        } else if (clipboard.hasFiles()) {
                            for (File file : clipboard.getFiles()) {
                                String name = file.getName().toLowerCase();
                                if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                                        || name.endsWith(".bmp")) {
                                    loadScreenshot(file);
                                    break;
                                }
                            }
                        }
                    }
                });
            }
        });
    }

    private void setupProfiles() {
        if (profileComboBox != null) {
            profileComboBox.setOnShowing(e -> {
                try {
                    List<AccountDescriptor> profiles = ProfileService.obtain().fetchAllAccounts();
                    if (profiles != null) {
                        AccountDescriptor currentlySelected = profileComboBox.getValue();
                        profileComboBox.setItems(FXCollections.observableArrayList(profiles));
                        profileComboBox.setConverter(new javafx.util.StringConverter<>() {
                            @Override
                            public String toString(AccountDescriptor p) {
                                return p == null ? "" : p.getName() + " (Emulator " + p.getEmulatorNumber() + ")";
                            }

                            @Override
                            public AccountDescriptor fromString(String string) {
                                return null;
                            }
                        });

                        if (currentlySelected != null) {
                            for (AccountDescriptor p : profiles) {
                                if (p.getEmulatorNumber() == currentlySelected.getEmulatorNumber()) {
                                    profileComboBox.getSelectionModel().select(p);
                                    break;
                                }
                            }
                        } else if (!profiles.isEmpty()) {
                            profileComboBox.getSelectionModel().selectFirst();
                        }
                    }
                } catch (Exception ex) {
                    log("Error loading profiles: " + ex.getMessage());
                }
            });
        }
    }

    private void loadScreenshot(File file) {
        try {
            Image image = new Image(new FileInputStream(file));
            screenshotImageView.setImage(image);
            dragDropHint.setVisible(false);
            currentScreenshotFile = file;
            log("Loaded screenshot: " + file.getAbsolutePath());
            log("Size: " + image.getWidth() + "x" + image.getHeight());
        } catch (FileNotFoundException e) {
            log("Error loading screenshot: " + e.getMessage());
        }
    }

    private void loadTemplates() {
        List<File> possibleTemplateDirs = new ArrayList<>();
        possibleTemplateDirs.add(new File("modules/vision/src/main/resources/templates"));
        possibleTemplateDirs.add(new File("../modules/vision/src/main/resources/templates"));
        possibleTemplateDirs.add(new File("../../modules/vision/src/main/resources/templates"));
        possibleTemplateDirs.add(new File("templates"));

        File currentDir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (currentDir != null && currentDir.exists()) {
            possibleTemplateDirs.add(new File(currentDir, "modules/vision/src/main/resources/templates"));
            currentDir = currentDir.getParentFile();
        }

        templateRootDir = null;
        for (File dir : possibleTemplateDirs) {
            if (dir.exists() && dir.isDirectory()) {
                templateRootDir = dir;
                break;
            }
        }

        allTemplates.clear();
        if (templateRootDir != null) {
            findImageFiles(templateRootDir, allTemplates);
            log("Found " + allTemplates.size() + " templates.");
            refreshTemplateFolderCombo();
        } else {
            log("Template directory not found. Please verify location.");
            log("User Dir: " + System.getProperty("user.dir"));
        }

        templateListView.getItems().setAll(allTemplates);
        templateListView.setCellFactory(param -> new ListCell<File>() {
            private final ImageView imageView = new ImageView();

            @Override
            protected void updateItem(File file, boolean empty) {
                super.updateItem(file, empty);
                if (empty || file == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(file.getName());
                    try {
                        Image icon = new Image(new FileInputStream(file), 32, 32, true, true);
                        imageView.setImage(icon);
                        setGraphic(imageView);
                    } catch (FileNotFoundException e) {
                        setGraphic(null);
                    }
                }
            }
        });
    }

    private void refreshTemplateFolderCombo() {
        if (templateFolderCombo == null || templateRootDir == null) {
            return;
        }
        List<String> folders = new ArrayList<>();
        folders.add("(templates root)");
        File[] children = templateRootDir.listFiles(File::isDirectory);
        if (children != null) {
            Arrays.sort(children, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File child : children) {
                folders.add(child.getName());
            }
        }
        String selected = templateFolderCombo.getValue();
        templateFolderCombo.setItems(FXCollections.observableArrayList(folders));
        if (selected != null && folders.contains(selected)) {
            templateFolderCombo.getSelectionModel().select(selected);
        } else {
            templateFolderCombo.getSelectionModel().selectFirst();
        }
    }

    private void findImageFiles(File dir, List<File> list) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    findImageFiles(file, list);
                } else {
                    String name = file.getName().toLowerCase();
                    if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".bmp")) {
                        list.add(file);
                    }
                }
            }
        }
    }

    private void setupActions() {
        actionComboBox.setItems(FXCollections.observableArrayList(
                "Template Search", "OCR", "Copy Point", "Copy Region", "Save Template"));
        actionComboBox.getSelectionModel().selectFirst();

        actionComboBox.valueProperty().addListener((observable, oldValue, newValue) -> updateActionVisibility(newValue));
        updateActionVisibility(actionComboBox.getValue());
    }

    private void updateActionVisibility(String action) {
        boolean isTemplateSearch = "Template Search".equals(action);
        boolean isSaveTemplate = "Save Template".equals(action);

        searchTextField.setVisible(isTemplateSearch);
        searchTextField.setManaged(isTemplateSearch);
        templateListView.setVisible(isTemplateSearch);
        templateListView.setManaged(isTemplateSearch);

        templateNameField.setVisible(isSaveTemplate);
        templateNameField.setManaged(isSaveTemplate);
        templateFolderCombo.setVisible(isSaveTemplate);
        templateFolderCombo.setManaged(isSaveTemplate);

        if (isSaveTemplate) {
            btnRunAction.setText("Save");
            refreshTemplateFolderCombo();
        } else {
            btnRunAction.setText("Run");
        }
    }

    @FXML
    private void handleRunAction(ActionEvent event) {
        String action = actionComboBox.getValue();

        if ("OCR".equals(action)) {
            performOCROnSelection();
            return;
        }
        if ("Copy Point".equals(action)) {
            if (selectionBox.isVisible() && selectionBox.getWidth() <= POINT_DRAG_THRESHOLD
                    && selectionBox.getHeight() <= POINT_DRAG_THRESHOLD) {
                copyPointFromInteraction(selectionBox.getX(), selectionBox.getY());
            } else if (selectionBox.isVisible()) {
                ImageRect rect = selectionToImageRect();
                if (rect != null && rect.isValid()) {
                    ImagePoint center = new ImagePoint(rect.x1() + rect.width() / 2, rect.y1() + rect.height() / 2);
                    copyPoint(center);
                } else {
                    log("Error: Click a point on the screenshot first.");
                }
            } else {
                log("Error: Click a point on the screenshot first.");
            }
            return;
        }
        if ("Copy Region".equals(action)) {
            copyRegionFromSelection();
            return;
        }
        if ("Save Template".equals(action)) {
            saveTemplateFromSelection();
            return;
        }

        File template = templateListView.getSelectionModel().getSelectedItem();

        if (currentScreenshotFile == null) {
            log("Error: No screenshot loaded. Please drag and drop an image first.");
            return;
        }

        if (template == null) {
            log("Error: Please select a template from the list.");
            return;
        }

        log("Running action: " + action + " with template: " + template.getName());

        try {
            String absPath = template.getAbsolutePath().replace("\\", "/");
            int templatesIndex = absPath.indexOf("/templates/");
            if (templatesIndex == -1) {
                log("Error: Selected template is not in a '/templates/' directory.");
                return;
            }
            String templateResourcePath = absPath.substring(templatesIndex);

            byte[] screenshotBytes = Files.readAllBytes(currentScreenshotFile.toPath());

            Image img = screenshotImageView.getImage();
            int width = (int) img.getWidth();
            int height = (int) img.getHeight();

            ImageSearchResultData result = OpenCvPatternLocator.locatePattern(screenshotBytes, templateResourcePath,
                    new PointData(0, 0), new PointData(width, height), 70.0);

            if (result.isFound()) {
                log("Match Found! Coordinates: X=" + result.getPoint().getX() + ", Y=" + result.getPoint().getY()
                        + " (Accuracy: " + String.format("%.2f", result.getMatchPercentage()) + "%)");
                displayCrosshairAt((int) result.getPoint().getX(), (int) result.getPoint().getY());
            } else {
                log("No match found. Best Match: " + String.format("%.2f", result.getMatchPercentage()) + "%");
            }
        } catch (Exception e) {
            log("Error executing OpenCvPatternLocator: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void copyPointFromInteraction(double containerX, double containerY) {
        boolean tinySelection = selectionBox.isVisible()
                && selectionBox.getWidth() <= POINT_DRAG_THRESHOLD
                && selectionBox.getHeight() <= POINT_DRAG_THRESHOLD;

        ImagePoint point;
        if (tinySelection || !selectionBox.isVisible() || selectionBox.getWidth() <= 0) {
            point = viewToImage(containerX, containerY);
        } else {
            ImageRect rect = selectionToImageRect();
            if (rect == null || !rect.isValid()) {
                point = viewToImage(containerX, containerY);
            } else {
                point = new ImagePoint(rect.x1() + rect.width() / 2, rect.y1() + rect.height() / 2);
            }
        }

        if (point == null) {
            log("Error: Click inside the screenshot to copy a point.");
            return;
        }
        copyPoint(point);
    }

    private void copyPoint(ImagePoint point) {
        String plain = point.asPlain();
        copyPlain(plain);
        log("Copied point: " + plain);
        displayCrosshairAt(point.x(), point.y());
    }

    private void copyRegionFromSelection() {
        ImageRect rect = selectionToImageRect();
        if (rect == null || !rect.isValid()) {
            log("Error: Drag a region on the screenshot first.");
            return;
        }
        String plain = rect.asPlain();
        copyPlain(plain);
        log("Copied region: " + plain);
    }

    private void saveTemplateFromSelection() {
        if (currentScreenshotFile == null || screenshotImageView.getImage() == null) {
            log("Error: No screenshot loaded.");
            return;
        }
        if (templateRootDir == null) {
            log("Error: Template directory not found.");
            return;
        }

        ImageRect rect = selectionToImageRect();
        if (rect == null || !rect.isValid()) {
            log("Error: Drag a region to crop before saving a template.");
            return;
        }

        String rawName = templateNameField.getText() == null ? "" : templateNameField.getText().trim();
        if (rawName.isEmpty()) {
            log("Error: Enter a template filename first.");
            return;
        }
        if (!rawName.toLowerCase().endsWith(".png")
                && !rawName.toLowerCase().endsWith(".jpg")
                && !rawName.toLowerCase().endsWith(".bmp")) {
            rawName = rawName + ".png";
        }
        if (rawName.contains("..") || rawName.contains("/") || rawName.contains("\\")) {
            log("Error: Filename must not contain path separators.");
            return;
        }

        String folder = templateFolderCombo.getValue();
        File targetDir = templateRootDir;
        if (folder != null && !folder.isEmpty() && !"(templates root)".equals(folder)) {
            targetDir = new File(templateRootDir, folder);
            if (!targetDir.exists() || !targetDir.isDirectory()) {
                log("Error: Subfolder does not exist: " + folder);
                return;
            }
        }

        File targetFile = new File(targetDir, rawName);
        try {
            BufferedImage source = ImageIO.read(currentScreenshotFile);
            if (source == null) {
                log("Error: Could not read screenshot image for cropping.");
                return;
            }
            BufferedImage crop = source.getSubimage(rect.x1(), rect.y1(), rect.width(), rect.height());
            String format = rawName.toLowerCase().endsWith(".jpg") ? "jpg"
                    : rawName.toLowerCase().endsWith(".bmp") ? "bmp" : "png";
            ImageIO.write(crop, format, targetFile);

            log("Saved template: " + targetFile.getAbsolutePath());
            log("Crop region: " + rect.asPlain() + " (" + rect.width() + "x" + rect.height() + ")");
            loadTemplates();
            if (searchTextField != null && !searchTextField.getText().isBlank()) {
                searchTextField.setText(searchTextField.getText());
            }
        } catch (Exception e) {
            log("Error saving template: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void displayCrosshairAt(int imgX, int imgY) {
        if (screenshotImageView.getImage() == null || imageContainer == null) {
            return;
        }

        Image screenshotImage = screenshotImageView.getImage();
        ViewSize view = currentViewSize();

        double offsetX = (imageContainer.getWidth() - view.width()) / 2.0;
        double offsetY = (imageContainer.getHeight() - view.height()) / 2.0;

        double relX = (imgX / screenshotImage.getWidth()) * view.width();
        double relY = (imgY / screenshotImage.getHeight()) * view.height();

        double uiX = relX + offsetX;
        double uiY = relY + offsetY;

        showCrosshairAtContainer(uiX, uiY, imgX, imgY);
        isCrosshairLocked = true;
    }

    private void performOCROnSelection() {
        if (currentScreenshotFile == null || screenshotImageView.getImage() == null) {
            log("Error: No screenshot loaded.");
            return;
        }

        ImageRect rect = selectionToImageRect();
        if (rect == null || !rect.isValid()) {
            log("Error: No region selected. Please click and drag to select a region.");
            return;
        }

        log(String.format("Performing OCR on Region: TopLeft(%d, %d), BottomRight(%d, %d)",
                rect.x1(), rect.y1(), rect.x2(), rect.y2()));

        try {
            String result = OcrEngine.readFromFile(currentScreenshotFile, rect.x1(), rect.y1(),
                    rect.width(), rect.height(), "eng");
            log("OCR Output:\n" + result);
        } catch (Exception e) {
            log("OCR Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @FXML
    private void handleCaptureAdb(ActionEvent event) {
        AccountDescriptor selected = profileComboBox.getValue();
        if (selected == null) {
            log("Error: Please select a profile first.");
            return;
        }

        Thread captureThread = new Thread(() -> {
            javafx.application.Platform.runLater(() -> {
                btnCaptureAdb.setDisable(true);
                log("Capturing screenshot from " + selected.getName() + " (Emulator " + selected.getEmulatorNumber()
                        + ")...");
            });

            try {
                try {
                    dev.frostguard.engine.emulator.EmulatorController.getInstance()
                            .captureScreen(selected.getEmulatorNumber());
                } catch (IllegalStateException e) {
                    if (e.getMessage().contains("Backend not initialised")) {
                        dev.frostguard.engine.emulator.EmulatorController.getInstance().initialize();
                    }
                }

                dev.frostguard.api.domain.RawImageData rawImage = dev.frostguard.engine.emulator.EmulatorController
                        .getInstance().captureScreen(selected.getEmulatorNumber());

                if (rawImage != null) {
                    BufferedImage bImg = dev.frostguard.vision.convert.ImageConverter.toBufferedImage(rawImage);

                    File tempFile = File.createTempFile("adb_screenshot", ".png");
                    ImageIO.write(bImg, "png", tempFile);

                    javafx.application.Platform.runLater(() -> {
                        log("Success: Saved to temporary file " + tempFile.getAbsolutePath());
                        loadScreenshot(tempFile);
                    });
                } else {
                    javafx.application.Platform.runLater(() -> log("Error: Failed to capture ADB image."));
                }
            } catch (Exception e) {
                javafx.application.Platform.runLater(() -> {
                    log("Adb Capture Error: " + e.getMessage());
                    e.printStackTrace();
                });
            } finally {
                javafx.application.Platform.runLater(() -> btnCaptureAdb.setDisable(false));
            }
        });
        captureThread.start();
    }

    private void log(String message) {
        logTextArea.appendText(message + "\n");
    }
}
