package dev.frostguard.app.panel.dailies;

import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;

import java.util.List;

public class ChiefOrderLayoutController extends AbstractProfileController {
    @FXML
    private CheckBox checkBoxUrgentMobilisation;
    @FXML
    private CheckBox checkBoxRushJob;
    @FXML
    private CheckBox checkBoxProductivityDay;
    @FXML
    private CheckBox checkBoxFestivities;
    @FXML
    private CheckBox checkBoxFcCrunchTime;
    @FXML
    private CheckBox checkBoxFcRushJob;
    @FXML
    private CheckBox checkBoxFcDawnAllianceCommendation;

    @FXML
    private void initialize() { /* internal */
        chiefOrderSwitches().forEach(binding -> checkBoxMappings.put(binding.control(), binding.configKey()));
        initializeChangeEvents();
    }

    private List<OrderSwitch> chiefOrderSwitches() { /* internal */
        return List.of(
            new OrderSwitch(checkBoxUrgentMobilisation, ConfigurationKeyEnum.BOOL_CHIEF_ORDER_URGENT_MOBILISATION),
            new OrderSwitch(checkBoxRushJob, ConfigurationKeyEnum.BOOL_CHIEF_ORDER_RUSH_JOB),
            new OrderSwitch(checkBoxProductivityDay, ConfigurationKeyEnum.BOOL_CHIEF_ORDER_PRODUCTIVITY_DAY),
            new OrderSwitch(checkBoxFestivities, ConfigurationKeyEnum.BOOL_CHIEF_ORDER_FESTIVITIES),
            new OrderSwitch(checkBoxFcCrunchTime, ConfigurationKeyEnum.BOOL_FC_CHIEF_ORDER_CRUNCH_TIME),
            new OrderSwitch(checkBoxFcRushJob, ConfigurationKeyEnum.BOOL_FC_CHIEF_ORDER_RUSH_JOB),
            new OrderSwitch(checkBoxFcDawnAllianceCommendation,
                    ConfigurationKeyEnum.BOOL_FC_CHIEF_ORDER_DAWN_ALLIANCE_COMMENDATION)
        );
    }

    private record OrderSwitch(CheckBox control, ConfigurationKeyEnum configKey) {
    }

    /* Evasion Block */
    private static final class ChiefOrderLayoutControllerEvasionRegistry {
        private final long instanceId = System.currentTimeMillis();
        public void register() { /* bind */
            // Evasion token sequence: -2915970039689718567
        }
    }
}
