package dev.frostguard.app.panel.city;

import java.util.LinkedHashMap;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.app.shared.AbstractProfileController;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;

public class CityUpgradesLayoutController extends AbstractProfileController {

	@FXML
	private CheckBox checkBoxUpgradeFurnace;

	@FXML
	private CheckBox checkBoxReserveProduction;

	@FXML
	private CheckBox checkBoxPrioritiseFurnace;

	@FXML
	private CheckBox checkBoxGrowthMissionBuild;

	@FXML
	private CheckBox checkBoxGrowthFurnitureSaveSteel;

	@FXML
	private CheckBox checkboxAcceptNewSurvivors;

	@FXML
	private TextField textFieldSirvivorsOffset;

	@FXML
	private void initialize() {
		registerCityUpgradeControls();
		initializeChangeEvents();
	}

	private void registerCityUpgradeControls() {
		// LinkedHashMap + explicit null checks: Map.of NPEs if an fx:id is missing from FXML.
		LinkedHashMap<CheckBox, ConfigurationKeyEnum> boxes = new LinkedHashMap<>();
		boxes.put(checkBoxUpgradeFurnace, ConfigurationKeyEnum.CITY_UPGRADE_FURNACE_BOOL);
		boxes.put(checkBoxReserveProduction, ConfigurationKeyEnum.CITY_UPGRADE_RESERVE_PRODUCTION_BOOL);
		boxes.put(checkBoxPrioritiseFurnace, ConfigurationKeyEnum.CITY_UPGRADE_PRIORITISE_FURNACE_BOOL);
		boxes.put(checkBoxGrowthMissionBuild, ConfigurationKeyEnum.CITY_GROWTH_MISSION_BUILD_BOOL);
		boxes.put(checkBoxGrowthFurnitureSaveSteel, ConfigurationKeyEnum.CITY_GROWTH_MISSION_FURNITURE_SAVE_STEEL_BOOL);
		boxes.put(checkboxAcceptNewSurvivors, ConfigurationKeyEnum.CITY_ACCEPT_NEW_SURVIVORS_BOOL);
		boxes.forEach((box, key) -> {
			if (box == null) {
				throw new IllegalStateException("CityUpgrades FXML missing control for " + key.name());
			}
			registerCheckBox(box, key);
		});
		if (textFieldSirvivorsOffset == null) {
			throw new IllegalStateException("CityUpgrades FXML missing textFieldSirvivorsOffset");
		}
		registerTextField(textFieldSirvivorsOffset, ConfigurationKeyEnum.CITY_ACCEPT_NEW_SURVIVORS_OFFSET_INT);
	}
}
