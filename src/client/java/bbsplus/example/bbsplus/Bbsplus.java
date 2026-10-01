package bbsplus.example.bbsplus;

import mchorse.bbs_mod.api.BBSAddonMod;
import mchorse.bbs_mod.api.BBSApi;
import net.fabricmc.api.ModInitializer;

public class Bbsplus implements ModInitializer, BBSAddonMod {

    public Bbsplus() {
        BBSApi.requireVersion("bbs-posecurve-addon", 1);
    }

    @Override
    public void onInitialize() {
    }
}
