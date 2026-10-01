package bbsplus.example.bbsplus.client;

import mchorse.bbs_mod.data.types.MapType;

public final class BbsplusCurveEditorState
{
    public final String sheetId;
    public final double xMin;
    public final double xMax;
    public final MapType graphState;

    public BbsplusCurveEditorState(String sheetId, double xMin, double xMax, MapType graphState)
    {
        this.sheetId = sheetId;
        this.xMin = xMin;
        this.xMax = xMax;
        this.graphState = graphState;
    }
}
