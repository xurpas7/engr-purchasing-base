package org.rmj.engr.purchasing.base;

import com.mysql.jdbc.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.json.simple.JSONObject;
import org.rmj.appdriver.constants.EditMode;
import org.rmj.appdriver.GCrypt;
import org.rmj.appdriver.GRider;
import org.rmj.appdriver.iface.GEntity;
import org.rmj.appdriver.iface.GTransaction;
import org.rmj.appdriver.MiscUtil;
import org.rmj.appdriver.SQLUtil;
import org.rmj.appdriver.agentfx.ShowMessageFX;
import org.rmj.appdriver.agentfx.ui.showFXDialog;
import org.rmj.appdriver.constants.TransactionStatus;
import org.rmj.appdriver.constants.UserRight;
import org.rmj.engr.inventory.base.InventoryTrans;
import org.rmj.engr.inventory.base.Inventory;
import org.engr.purchasing.pojo.UnitPOReceivingDetail;
import org.engr.purchasing.pojo.UnitPOReceivingDetailOthers;
import org.engr.purchasing.pojo.UnitPOReceivingMaster;
import org.rmj.appdriver.agentfx.CommonUtils;

public class POReceiving implements GTransaction{
    @Override
    public UnitPOReceivingMaster newTransaction() {
        UnitPOReceivingMaster loObj = new UnitPOReceivingMaster();
        Connection loConn = null;
        loConn = setConnection();
        
        loObj.setTransNox(MiscUtil.getNextCode(loObj.getTable(), "sTransNox", true, loConn, psBranchCd));
        
        //init detail
        poDetail = new ArrayList<>();
        paDetailOthers = new ArrayList<>();
        
        return loObj;
    }

    @Override
    public UnitPOReceivingMaster loadTransaction(String fsTransNox) {
        UnitPOReceivingMaster loObject = new UnitPOReceivingMaster();
        
        Connection loConn = null;
        loConn = setConnection();   
        
        String lsSQL = MiscUtil.addCondition(getSQ_Master(), "sTransNox = " + SQLUtil.toSQL(fsTransNox));
        ResultSet loRS = poGRider.executeQuery(lsSQL);
        
        try {
            if (!loRS.next()){
                setMessage("No Record Found");
            }else{
                //load each column to the entity
                for(int lnCol=1; lnCol<=loRS.getMetaData().getColumnCount(); lnCol++){
                    loObject.setValue(lnCol, loRS.getObject(lnCol));
                }
                
                //load detail
                poDetail = loadTransDetail(fsTransNox);
            }              
        } catch (SQLException ex) {
            setErrMsg(ex.getMessage());
        } finally{
            MiscUtil.close(loRS);
            if (!pbWithParent) MiscUtil.close(loConn);
        }
        
        return loObject;
    }
    
    @Override
    public UnitPOReceivingMaster saveUpdate(Object foEntity, String fsTransNox) {
        String lsSQL = "";
        
        UnitPOReceivingMaster loOldEnt = null;
        UnitPOReceivingMaster loNewEnt = null;
        UnitPOReceivingMaster loResult = null;
        
        // Check for the value of foEntity
        if (!(foEntity instanceof UnitPOReceivingMaster)) {
            setErrMsg("Invalid Entity Passed as Parameter");
            return loResult;
        }
        
        // Typecast the Entity to this object
        loNewEnt = (UnitPOReceivingMaster) foEntity;
        
        
        //validate project code
        if (loNewEnt.getBranchCd()== null || loNewEnt.getBranchCd().isEmpty()){
            setMessage("Invalid project name detected.");
            return loResult;
        }
        
        //validate transaction date
        if (loNewEnt.getDateTransact()== null){
            setMessage("Invalid transact date detected.");
            return loResult;
        }
        
        //validate inventory type
        if (loNewEnt.getInvTypeCd()== null || loNewEnt.getInvTypeCd().isEmpty()){
            setMessage("Invalid inventory type detected.");
            return loResult;
        }
        
        //validate supplier
        if (loNewEnt.getSupplier() == null || loNewEnt.getSupplier().isEmpty()){
            setMessage("Invalid supplier detected.");
            return loResult;
        }
        
        if (System.getProperty("store.inventory.strict.type").equals("1")){
            if (poDetail.get(ItemCount()-1).getStockID().equals("")) deleteDetail(ItemCount() -1);
        }
        
        if (ItemCount() <= 0){
            setMessage("Unable to save no item record.");
            return loResult;
        }
        
        //validate detail if PO exists
        //if (!isItemsHasPO(loNewEnt.getSourceNo())) return loResult;
        
        if (!pbWithParent) poGRider.beginTrans();
        
        // Generate the SQL Statement
        if (fsTransNox.equals("")){
            try {
                Connection loConn = null;
                loConn = setConnection();
                
                String lsTransNox = MiscUtil.getNextCode(loNewEnt.getTable(), "sTransNox", true, loConn, psBranchCd);
                
                loNewEnt.setTransNox(lsTransNox);
                loNewEnt.setModifiedBy(psUserIDxx);
                loNewEnt.setDateModified(poGRider.getServerDate());
                
                loNewEnt.setPreparedBy(psUserIDxx);
                loNewEnt.setDatePrepared(poGRider.getServerDate());
                
                //save detail first
                if(!saveDetail(loNewEnt, true)){
                    poGRider.rollbackTrans();
                    return null;
                }
                
                loNewEnt.setEntryNox(ItemCount());
                
                if (!pbWithParent) MiscUtil.close(loConn);
                
                //Generate the SQL Statement
                lsSQL = MiscUtil.makeSQL((GEntity) loNewEnt);
            } catch (SQLException ex) {
                Logger.getLogger(POReceiving.class.getName()).log(Level.SEVERE, null, ex);
            }
        }else{
            try {
                //Load previous transaction
                loOldEnt = loadTransaction(fsTransNox);
                
                //save detail first
                if(!saveDetail(loNewEnt, true)){
                    poGRider.rollbackTrans();
                    return null;
                }
                
                loNewEnt.setEntryNox(ItemCount());
                loNewEnt.setDateModified(poGRider.getServerDate());
                
                //Generate the Update Statement
                lsSQL = MiscUtil.makeSQL((GEntity) loNewEnt, (GEntity) loOldEnt, "sTransNox = " + SQLUtil.toSQL(loNewEnt.getTransNox()));
            } catch (SQLException ex) {
                Logger.getLogger(POReceiving.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        
        //No changes have been made
        if (lsSQL.equals("")){
            setMessage("Record is not updated");
            return loResult;
        }
        
        if(poGRider.executeQuery(lsSQL, loNewEnt.getTable(), "", "") == 0){
            if(!poGRider.getErrMsg().isEmpty())
                setErrMsg(poGRider.getErrMsg());
            else
            setMessage("No record updated");
        } else loResult = loNewEnt;
        
        if (!pbWithParent) {
            if (!getErrMsg().isEmpty()){
                poGRider.rollbackTrans();
            } else poGRider.commitTrans();
        }        
        
        return loResult;
    }

    @Override
    public boolean deleteTransaction(String fsTransNox) {
        UnitPOReceivingMaster loObject = loadTransaction(fsTransNox);
        boolean lbResult = false;
        
        if (loObject == null){
            setMessage("No record found...");
            return lbResult;
        }
        
        String lsSQL = "DELETE FROM " + loObject.getTable() + 
                        " WHERE sTransNox = " + SQLUtil.toSQL(fsTransNox);
        
        if (!pbWithParent) poGRider.beginTrans();
        
        if (poGRider.executeQuery(lsSQL, loObject.getTable(), "", "") == 0){
            if (!poGRider.getErrMsg().isEmpty()){
                setErrMsg(poGRider.getErrMsg());
            } else setErrMsg("No record deleted.");  
        } else lbResult = true;
        
        //delete detail rows
        lsSQL = "DELETE FROM " + pxeDetTable +
                " WHERE sTransNox = " + SQLUtil.toSQL(fsTransNox);
        
        if (poGRider.executeQuery(lsSQL, pxeDetTable, "", "") == 0){
            if (!poGRider.getErrMsg().isEmpty()){
                setErrMsg(poGRider.getErrMsg());
            } else setErrMsg("No record deleted.");  
        } else lbResult = true;
        
        if (!pbWithParent){
            if (getErrMsg().isEmpty()){
                poGRider.commitTrans();
            } else poGRider.rollbackTrans();
        }
        
        return lbResult;
    }

    public boolean closeTransaction(String fsTransNox, String fsApprovalCode) {
        UnitPOReceivingMaster loObject = loadTransaction(fsTransNox);
        boolean lbResult = false;
        
        if (loObject == null){
            setMessage("No record found...");
            return lbResult;
        }
        
        if (fsApprovalCode == null || fsApprovalCode.isEmpty()){
            setMessage("Invalid/No approval code detected.");
            return lbResult;
        }
        
        if (!loObject.getTranStat().equalsIgnoreCase(TransactionStatus.STATE_OPEN)){
            setMessage("Unable to close closed/cancelled/posted/voided transaction.");
            return lbResult;
        }
        
        String lsSQL = "UPDATE " + loObject.getTable() + 
                        " SET  cTranStat = " + SQLUtil.toSQL(TransactionStatus.STATE_CLOSED) + 
                            ", sApproved = " + SQLUtil.toSQL(psUserIDxx) +
                            ", dApproved = " + SQLUtil.toSQL(poGRider.getServerDate()) + 
                            ", sAprvCode = " + SQLUtil.toSQL(fsApprovalCode) +
                            ", sModified = " + SQLUtil.toSQL(psUserIDxx) +
                            ", dModified = " + SQLUtil.toSQL(poGRider.getServerDate()) + 
                        " WHERE sTransNox = " + SQLUtil.toSQL(loObject.getTransNox());
        
        if (!pbWithParent) poGRider.beginTrans();
        
        if (poGRider.executeQuery(lsSQL, loObject.getTable(), "", "") == 0){
            if (!poGRider.getErrMsg().isEmpty()){
                setErrMsg(poGRider.getErrMsg());
            } else setErrMsg("No record deleted.");  
        } else lbResult = saveInvTrans(loObject);
                
        if (!pbWithParent){
            if (getErrMsg().isEmpty()){
                poGRider.commitTrans();
            } else 
                poGRider.rollbackTrans();
        }
        return lbResult;
    }

    @Override
    public boolean postTransaction(String fsTransNox) {
        if (poGRider.getUserLevel() <= UserRight.ENCODER){
            JSONObject loJSON = showFXDialog.getApproval(poGRider);
            
            if (loJSON == null){
                ShowMessageFX.Warning("Approval failed.", pxeModuleName, "Unable to post transaction");
            }
            
            if ((int) loJSON.get("nUserLevl") <= UserRight.ENCODER){
                ShowMessageFX.Warning("User account has no right to approve.", pxeModuleName, "Unable to post transaction");
                return false;
            }
        }

        UnitPOReceivingMaster loObject = loadTransaction(fsTransNox);
        boolean lbResult = false;
        
        if (loObject == null){
            setMessage("No record found...");
            return lbResult;
        }
        
        if (loObject.getTranStat().equalsIgnoreCase(TransactionStatus.STATE_POSTED) ||
            loObject.getTranStat().equalsIgnoreCase(TransactionStatus.STATE_CANCELLED) ||
            loObject.getTranStat().equalsIgnoreCase(TransactionStatus.STATE_VOID)){
            setMessage("Unable to post cancelled/posted/voided transaction.");
            return lbResult;
        }
        
        String lsSQL = "UPDATE " + loObject.getTable() + 
                        " SET  cTranStat = " + SQLUtil.toSQL(TransactionStatus.STATE_POSTED) + 
                            ", sPostedxx = " + SQLUtil.toSQL(psUserIDxx) +
                            ", dPostedxx = " + SQLUtil.toSQL(poGRider.getServerDate()) + 
                            ", sModified = " + SQLUtil.toSQL(psUserIDxx) +
                            ", dModified = " + SQLUtil.toSQL(poGRider.getServerDate()) + 
                        " WHERE sTransNox = " + SQLUtil.toSQL(loObject.getTransNox());
        
        if (!pbWithParent) poGRider.beginTrans();
        
        if (poGRider.executeQuery(lsSQL, loObject.getTable(), "", "") == 0){
            if (!poGRider.getErrMsg().isEmpty()){
                setErrMsg(poGRider.getErrMsg());
            } else setErrMsg("No record deleted.");  
        } else lbResult = true;
        
        if (!pbWithParent){
            if (getErrMsg().isEmpty()){
                poGRider.commitTrans();
            } else poGRider.rollbackTrans();
        }
        return lbResult;
    }

    @Override
    public boolean voidTransaction(String fsTransNox) {
        UnitPOReceivingMaster loObject = loadTransaction(fsTransNox);
        boolean lbResult = false;
        
        if (loObject == null){
            setMessage("No record found...");
            return lbResult;
        }
        
        if (loObject.getTranStat().equalsIgnoreCase(TransactionStatus.STATE_POSTED)){
            setMessage("Unable to void posted transaction.");
            return lbResult;
        }
        
        String lsSQL = "UPDATE " + loObject.getTable() + 
                        " SET  cTranStat = " + SQLUtil.toSQL(TransactionStatus.STATE_VOID) + 
                            ", dModified = " + SQLUtil.toSQL(poGRider.getServerDate()) + 
                        " WHERE sTransNox = " + SQLUtil.toSQL(loObject.getTransNox());
        
        if (!pbWithParent) poGRider.beginTrans();
        
        if (poGRider.executeQuery(lsSQL, loObject.getTable(), "", "") == 0){
            if (!poGRider.getErrMsg().isEmpty()){
                setErrMsg(poGRider.getErrMsg());
            } else setErrMsg("No record deleted.");  
        } else lbResult = true;
        
        if (!pbWithParent){
            if (getErrMsg().isEmpty()){
                poGRider.commitTrans();
            } else poGRider.rollbackTrans();
        }
        return lbResult;
    }

    @Override
    public boolean cancelTransaction(String fsTransNox) {
        UnitPOReceivingMaster loObject = loadTransaction(fsTransNox);
        boolean lbResult = false;
        
        if (loObject == null){
            setMessage("No record found...");
            return lbResult;
        }
               
        if (loObject.getTranStat().equalsIgnoreCase(TransactionStatus.STATE_CANCELLED) ||
            loObject.getTranStat().equalsIgnoreCase(TransactionStatus.STATE_POSTED) ||
            loObject.getTranStat().equalsIgnoreCase(TransactionStatus.STATE_VOID)){
            setMessage("Unable to cancel cancelled/posted/voided transaction.");
            return lbResult;
        }
        
        String lsSQL = "UPDATE " + loObject.getTable() + 
                        " SET  cTranStat = " + SQLUtil.toSQL(TransactionStatus.STATE_CANCELLED) + 
                            ", dModified = " + SQLUtil.toSQL(poGRider.getServerDate()) + 
                        " WHERE sTransNox = " + SQLUtil.toSQL(loObject.getTransNox());
        
        if (!pbWithParent) poGRider.beginTrans();
        
        if (poGRider.executeQuery(lsSQL, loObject.getTable(), "", "") == 0){
            if (!poGRider.getErrMsg().isEmpty()){
                setErrMsg(poGRider.getErrMsg());
            } else setErrMsg("No record deleted.");  
        } else lbResult = true;
        
        if (!pbWithParent){
            if (getErrMsg().isEmpty()){
                poGRider.commitTrans();
            } else poGRider.rollbackTrans();
        }
        return lbResult;
    }

    @Override
    public String getMessage() {
        return psWarnMsg;
    }

    @Override
    public void setMessage(String fsMessage) {
        this.psWarnMsg = fsMessage;
    }

    @Override
    public String getErrMsg() {
        return psErrMsgx;
    }

    @Override
    public void setErrMsg(String fsErrMsg) {
        this.psErrMsgx = fsErrMsg;
    }

    @Override
    public void setBranch(String foBranchCD) {
        this.psBranchCd = foBranchCD;
    }

    @Override
    public void setWithParent(boolean fbWithParent) {
        this.pbWithParent = fbWithParent;
    }

    @Override
    public String getSQ_Master() {
        return "SELECT" +
                    "  sTransNox" +
                    ", sBranchCd" +
                    ", dTransact" +
                    ", sCompnyID" +
                    ", sSupplier" +
                    ", sReferNox" +
                    ", dRefernce" +
                    ", sTermCode" +
                    ", nTranTotl" +
                    ", nVATRatex" +
                    ", nTWithHld" +
                    ", nDiscount" +
                    ", nAddDiscx" +
                    ", nAmtPaidx" +
                    ", nFreightx" +
                    ", sRemarksx" +
                    ", sSourceNo" +
                    ", sSourceCd" +
                    ", nEntryNox" +
                    ", sInvTypCd" +
                    ", cTranStat" +
                    ", sPrepared" +
                    ", dPrepared" +
                    ", sApproved" +
                    ", dApproved" +
                    ", sAprvCode" +
                    ", sPostedxx" +
                    ", dPostedxx" +
                    ", sDeptIDxx" +
                    ", cDivision" +
                    ", sModified" +
                    ", dModified" +
                " FROM " + pxeMasTable;
    }
    
    public int ItemCount(){
        return poDetail.size();
    }
    
    public boolean addDetail() {
        if (poDetail.isEmpty()){
            poDetail.add(new UnitPOReceivingDetail());
            paDetailOthers.add(new UnitPOReceivingDetailOthers());
        }else{
            if ((!poDetail.get(ItemCount()-1).getStockID().equals("") ||
                !paDetailOthers.get(ItemCount()-1).getValue(10).equals("")) &&
                poDetail.get(ItemCount() -1).getQuantity()!= 0){
                poDetail.add(new UnitPOReceivingDetail());
                paDetailOthers.add(new UnitPOReceivingDetailOthers());
            } else return false; 
        }
        return true;
    }

    public boolean deleteDetail(int fnEntryNox) {       
        poDetail.remove(fnEntryNox);
        paDetailOthers.remove(fnEntryNox);
        
        if (poDetail.isEmpty() ||
            !poDetail.get(ItemCount() -1).getStockID().equals("")) return addDetail();
        
        return true;
    }
    
    public void setDetail(int fnRow, int fnCol, Object foData){
        switch (fnCol){
            case 8: //nUnitPrce
            case 9: //nFreightx
                if (foData instanceof Number){
                    poDetail.get(fnRow).setValue(fnCol, foData);
                }else poDetail.get(fnRow).setValue(fnCol, 0);
                break;
            case 7: //nQuantity
                if (foData instanceof Integer){
                    poDetail.get(fnRow).setValue(fnCol, foData);
                }else poDetail.get(fnRow).setValue(fnCol, 0);
                break;
            case 100: //xBarCodex
                paDetailOthers.get(fnRow).setValue(9, foData);
                break;
            case 101: //xDescript
                paDetailOthers.get(fnRow).setValue(10, foData);
                break;
            default:
                poDetail.get(fnRow).setValue(fnCol, foData);
        }
    }
    public void setDetail(int fnRow, String fsCol, Object foData){
        switch(fsCol){
            case "nUnitPrce":
            case "nFreightx":
                if (foData instanceof Number){
                    poDetail.get(fnRow).setValue(fsCol, foData);
                }else poDetail.get(fnRow).setValue(fsCol, 0);
                break;
            case "nQuantity":
                if (foData instanceof Integer){
                    poDetail.get(fnRow).setValue(fsCol, foData);
                }else poDetail.get(fnRow).setValue(fsCol, 0);
                break;
            case "xBarCodex":
                setDetail(fnRow, 9, foData);
                break;
            case "xDescript":
                setDetail(fnRow, 10, foData);
                break;
            default:
                poDetail.get(fnRow).setValue(fsCol, foData);
        }
    }
    
    public Object getDetail(int fnRow, int fnCol){
        switch(fnCol){
            case 100:
                return paDetailOthers.get(fnRow).getValue(9);
            case 101:
                return paDetailOthers.get(fnRow).getValue(10);
            default:
                return poDetail.get(fnRow).getValue(fnCol);
        }
    }
    
    public Object getDetail(int fnRow, String fsCol){
        switch(fsCol){
            case "xBarCodex":
                return getDetail(fnRow, 9);
            case "xDescript":
                return getDetail(fnRow, 10);
            default:
                return poDetail.get(fnRow).getValue(fsCol);
        }       
    }
    
    private boolean isItemsHasPO(String fsSourceNo){
        String lsSQL;
        ResultSet loRS;
        
        for (int lnCtr = 0; lnCtr <= ItemCount()-1; lnCtr++){ 
            lsSQL = poDetail.get(lnCtr).getOrderNox().isEmpty() ? fsSourceNo : poDetail.get(lnCtr).getOrderNox();
            
            lsSQL = "SELECT" +
                        " (nQuantity - nReceived) xQuantity" + 
                    " FROM PO_Detail" +
                    " WHERE sTransNox = " + SQLUtil.toSQL(lsSQL) + 
                        " AND sStockIDx = " + SQLUtil.toSQL(poDetail.get(lnCtr).getStockID());
            
            loRS = poGRider.executeQuery(lsSQL);
            
            try {
                if (loRS.next()){
                    if (loRS.getInt("xQuantity") <= 0){
                        setMessage("All orders for this item was already served. [" + 
                                    paDetailOthers.get(lnCtr).getValue("xDescript") + "(" + paDetailOthers.get(lnCtr).getValue("xBarCodex") +  ")]" +
                                    "\n\n Kindly check the ORDER NO. or REFERENCE P.O. if correct.");
                        return false;
                    }
                } else{
                    setMessage("No orders found for this item. [" + 
                                paDetailOthers.get(lnCtr).getValue("xDescript") + "(" + paDetailOthers.get(lnCtr).getValue("xBarCodex") +  ")]" +
                                "\n\n Kindly check the ORDER NO. or REFERENCE P.O. if correct.");
                    return false;
                }    
            } catch (SQLException ex) {
                ex.printStackTrace();
                setMessage("SQL Exception Detected.");
                setErrMsg(ex.getMessage());
                return false;
            }
        }    
        
        return true;
    }
    
    private boolean saveDetail(UnitPOReceivingMaster foData, boolean fbNewRecord) throws SQLException{
        if (ItemCount() <= 0){
            setMessage("No transaction detail detected.");
            return false;
        }
        
        UnitPOReceivingDetail loOldDet;
        
        String lsSQL;
        
        int lnCtr;
        int lnRow = 0;
        
        for (lnCtr = 0; lnCtr <= ItemCount() -1; lnCtr++){     
            if (System.getProperty("store.inventory.strict.type").equals("0")){
                if (poDetail.get(lnCtr).getStockID().equals("") &&
                    !paDetailOthers.get(lnCtr).getValue(10).equals("")){

                    //create inventory.
                    Inventory loInv = new Inventory(poGRider, psBranchCd, true);
                    loInv.NewRecord();

                    if (paDetailOthers.get(lnCtr).getValue(9).equals(""))
                        loInv.setMaster("sBarCodex", CommonUtils.getNextReference(poGRider.getConnection(), "Inventory", "sBarCodex", true));
                    else
                        loInv.setMaster("sBarCodex", paDetailOthers.get(lnCtr).getValue(9));

                    loInv.setMaster("sDescript", paDetailOthers.get(lnCtr).getValue(10));
                    loInv.setMaster("sInvTypCd", foData.getInvTypeCd());
                    loInv.setMaster("nUnitPrce", poDetail.get(lnCtr).getUnitPrice());
                    loInv.setMaster("nSelPrice", poDetail.get(lnCtr).getUnitPrice());
                    if (!loInv.SaveRecord()){
                        setMessage(loInv.getErrMsg() + "; " + loInv.getMessage());
                        return false;
                    }

                    poDetail.get(lnCtr).setStockID((String) loInv.getMaster("sStockIDx"));
                }
            }
            
            poDetail.get(lnCtr).setTransNox(foData.getTransNox());
            poDetail.get(lnCtr).setEntryNox(lnCtr + 1);
            poDetail.get(lnCtr).setDateModified(poGRider.getServerDate());
            
            if (!poDetail.get(lnCtr).getStockID().equals("")){
                if (fbNewRecord){
                    //Generate the SQL Statement
                    lsSQL = MiscUtil.makeSQL((GEntity) poDetail.get(lnCtr));
                }else{
                    //Load previous transaction
                    loOldDet = loadTransDetail(foData.getTransNox(), lnCtr + 1);

                    //Generate the Update Statement
                    lsSQL = MiscUtil.makeSQL((GEntity) poDetail.get(lnCtr), (GEntity) loOldDet, 
                                                "sTransNox = " + SQLUtil.toSQL(poDetail.get(lnCtr).getTransNox()) + 
                                                    " AND nEntryNox = " + poDetail.get(lnCtr).getEntryNox());
                }

                if (!lsSQL.equals("")){
                    if(poGRider.executeQuery(lsSQL, pxeDetTable, "", "") == 0){
                        if(!poGRider.getErrMsg().isEmpty()){ 
                            setErrMsg(poGRider.getErrMsg());
                            return false;
                        }
                        
                    }else {
                        lnRow += 1;
                    }
                }
            }
        }    
        
        if (lnRow == 0) {
            setMessage("No record to update.");
            return false;
        }
        
        //check if the new detail is less than the original detail count
        lnRow = loadTransDetail(foData.getTransNox()).size();
        if (lnCtr < lnRow -1){
            for (lnCtr = lnCtr + 1; lnCtr <= lnRow; lnCtr++){
                lsSQL = "DELETE FROM " + pxeDetTable +  
                        " WHERE sTransNox = " + SQLUtil.toSQL(foData.getTransNox()) + 
                            " AND nEntryNox = " + lnCtr;
                
                if(poGRider.executeQuery(lsSQL, pxeDetTable, "", "") == 0){
                    if(!poGRider.getErrMsg().isEmpty()) setErrMsg(poGRider.getErrMsg());
                }else {
                    setMessage("No record updated");
                    return false;
                }
            }
        }
        
        return true;
    }
    
    private UnitPOReceivingDetail loadTransDetail(String fsTransNox, int fnEntryNox) throws SQLException{
        UnitPOReceivingDetail loObj = null;
        ResultSet loRS = poGRider.executeQuery(
                            MiscUtil.addCondition(getSQ_Detail(), 
                                                    "sTransNox = " + SQLUtil.toSQL(fsTransNox)) + 
                                                    " AND nEntryNox = " + fnEntryNox);
        
        if (!loRS.next()){
            setMessage("No Record Found");
        }else{
            //load each column to the entity
            loObj = new UnitPOReceivingDetail();
            for(int lnCol=1; lnCol<=loRS.getMetaData().getColumnCount(); lnCol++){
                loObj.setValue(lnCol, loRS.getObject(lnCol));
            }
        }      
        return loObj;
    }
    
    private ArrayList<UnitPOReceivingDetail> loadTransDetail(String fsTransNox) throws SQLException{
        UnitPOReceivingDetail loOcc = null;
        UnitPOReceivingDetailOthers loOth = null;
        Connection loConn = null;
        loConn = setConnection();
        
        ArrayList<UnitPOReceivingDetail> loDetail = new ArrayList<>();
        paDetailOthers = new ArrayList<>(); //reset detail others
        
        ResultSet loRS = poGRider.executeQuery(
                            MiscUtil.addCondition(getSQ_Detail(), 
                                                    "sTransNox = " + SQLUtil.toSQL(fsTransNox)));
        
        for (int lnCtr = 1; lnCtr <= MiscUtil.RecordCount(loRS); lnCtr ++){
            loRS.absolute(lnCtr);
            
            loOcc = new UnitPOReceivingDetail();
            loOcc.setValue("sTransNox", loRS.getString("sTransNox"));        
            loOcc.setValue("nEntryNox", loRS.getInt("nEntryNox"));
            loOcc.setValue("sOrderNox", loRS.getString("sOrderNox"));
            loOcc.setValue("sStockIDx", loRS.getString("sStockIDx"));
            loOcc.setValue("sReplacID", loRS.getString("sReplacID"));
            loOcc.setValue("cUnitType", loRS.getString("cUnitType"));
            loOcc.setValue("nQuantity", loRS.getInt("nQuantity"));
            loOcc.setValue("nUnitPrce", loRS.getDouble("nUnitPrce"));
            loOcc.setValue("nFreightx", loRS.getDouble("nFreightx"));
            loOcc.setValue("dModified", loRS.getDate("dModified"));
            loDetail.add(loOcc);
            
            //load other info
            loOth = new UnitPOReceivingDetailOthers();
            loOth.setValue("sStockIDx", loRS.getString("sStockIDx"));
            loOth.setValue("nQtyOnHnd", loRS.getInt("nQtyOnHnd"));
            loOth.setValue("xQtyOnHnd", loRS.getInt("xQtyOnHnd"));
            loOth.setValue("nResvOrdr", loRS.getInt("nResvOrdr"));
            loOth.setValue("nBackOrdr", loRS.getInt("nBackOrdr"));
            loOth.setValue("nReorderx", 0);
            loOth.setValue("nLedgerNo", loRS.getInt("nLedgerNo"));
            paDetailOthers.add(loOth);
        }
        
        return loDetail;
    }
    
    private String getSQ_Detail(){
        return "SELECT" +
                    "  a.sTransNox" +
                    ", a.nEntryNox" +
                    ", a.sOrderNox" +
                    ", a.sStockIDx" +
                    ", a.sReplacID" +
                    ", a.cUnitType" +
                    ", a.nQuantity" +
                    ", a.nUnitPrce" +
                    ", a.nFreightx" +
                    ", a.dModified" +
                    ", IFNULL(b.nQtyOnHnd, 0) nQtyOnHnd" + 
                    ", IFNULL(b.nQtyOnHnd, 0) + a.nQuantity xQtyOnHnd" + 
                    ", IFNULL(b.nResvOrdr, 0) nResvOrdr" +
                    ", IFNULL(b.nBackOrdr, 0) nBackOrdr" +
                    ", IFNULL(b.nFloatQty, 0) nFloatQty" +
                    ", IFNULL(b.nLedgerNo, 0) nLedgerNo" +
                " FROM " + pxeDetTable + " a" +
                    " LEFT JOIN Inventory d" + 
                        " ON a.sReplacID = d.sStockIDx" + 
                    " LEFT JOIN Inv_Master b" +
                        " ON a.sStockIDx = b.sStockIDx" + 
                            " AND b.sBranchCD = " + SQLUtil.toSQL(psBranchCd) +
                    " LEFT JOIN Inventory c" + 
                        " ON b.sStockIDx = c.sStockIDx" +  
                " ORDER BY a.nEntryNox";
    }
    
    //Added methods
    private boolean saveInvTrans(UnitPOReceivingMaster foValue){
        String lsSQL;
        ResultSet loRS;
        
        InventoryTrans loInvTrans = new InventoryTrans(poGRider, foValue.getBranchCd());
        loInvTrans.InitTransaction();
        
        for (int lnCtr = 0; lnCtr <= poDetail.size() - 1; lnCtr ++){
            if (poDetail.get(lnCtr).getStockID().equals("")) break;
            loInvTrans.setDetail(lnCtr, "sStockIDx", poDetail.get(lnCtr).getStockID());
            loInvTrans.setDetail(lnCtr, "sReplacID", poDetail.get(lnCtr).getReplaceID());
            loInvTrans.setDetail(lnCtr, "nQuantity", poDetail.get(lnCtr).getQuantity());
            loInvTrans.setDetail(lnCtr, "nQtyInxxx", poDetail.get(lnCtr).getQuantity());
            loInvTrans.setDetail(lnCtr, "nUnitPrce", poDetail.get(lnCtr).getUnitPrice());
            loInvTrans.setDetail(lnCtr, "nQtyOnHnd", paDetailOthers.get(lnCtr).getValue("nQtyOnHnd"));
            loInvTrans.setDetail(lnCtr, "nResvOrdr", paDetailOthers.get(lnCtr).getValue("nResvOrdr"));
            loInvTrans.setDetail(lnCtr, "nBackOrdr", paDetailOthers.get(lnCtr).getValue("nBackOrdr"));
            loInvTrans.setDetail(lnCtr, "nLedgerNo", paDetailOthers.get(lnCtr).getValue("nLedgerNo"));
            
            String lsSourceNo = poDetail.get(lnCtr).getOrderNox().isEmpty() ? foValue.getSourceNo() : poDetail.get(lnCtr).getOrderNox();
            
            //get the order information
            lsSQL = "SELECT" +
                        "  sStockIDx" +
                        ", nQuantity" + 
                        ", nUnitPrce" + 
                    " FROM PO_Detail" +
                    " WHERE sTransNox = " + SQLUtil.toSQL(lsSourceNo) +
                        " AND sStockIDx = " + SQLUtil.toSQL(poDetail.get(lnCtr).getReplaceID().isEmpty() ? poDetail.get(lnCtr).getStockID() : poDetail.get(lnCtr).getReplaceID());
            
            loRS = poGRider.executeQuery(lsSQL);
            
            try {
                if (loRS.next()){                    
                    //update the order information
                    lsSQL = "UPDATE PO_Detail SET" +
                                "  nReceived = nReceived + " + poDetail.get(lnCtr).getQuantity() +
                                ", dModified = " + SQLUtil.toSQL(poGRider.getServerDate()) +
                            " WHERE sTransNox = " + SQLUtil.toSQL(lsSourceNo) +
                                " AND sStockIDx = " + SQLUtil.toSQL(loRS.getString("sStockIDx"));
                    
                    if (poGRider.executeQuery(lsSQL, "PO_Detail", poGRider.getBranchCode(), "") <= 0 ){
                        setMessage(poGRider.getMessage());
                        setErrMsg(poGRider.getErrMsg());
                        return false;
                    }
                }
            } catch (SQLException ex) {
                setErrMsg(ex.getMessage());
                return false;
            }
        }
        
        if (!loInvTrans.POReceiving(foValue.getTransNox(), poGRider.getServerDate(), foValue.getSupplier(), EditMode.ADDNEW)){
            setMessage(loInvTrans.getMessage());
            setErrMsg(loInvTrans.getErrMsg());
            return false;
        }
        
        return true;
    }
    
    private boolean saveOrderUpdate(String fsTransNox, boolean fbSave){
        String lsSQL = "SELECT" +
                            "  IF(IFNULL(a.sOrderNox, '') = '', IF(IFNULL(b.sSourceNo, '') = '', '', b.sSourceNo), a.sOrderNox) xOrderNox" +
                            ", IF(IFNULL(a.sStockIDx, '') = '', a.sReplacID, a.sStockIDx) xStockIDx" +
                            ", a.sReplacID" +
                            ", a.nQuantity" +
                            ", a.nUnitPrce" + 
                        " FROM PO_Receiving_Detail a" +
                            " LEFT JOIN PO_Receiving_Master b" +
                                " ON a.sTransNox = b.sTransNox" +
                        " WHERE a.sTransNox = " + SQLUtil.toSQL(fsTransNox) +
                        " ORDER BY a.nEntryNox";
        
        ResultSet loRS = poGRider.executeQuery(lsSQL);
                
        try {
            while (loRS.next()){
                if (!loRS.getString("xOrderNox").isEmpty()){
                    lsSQL = "UPDATE PO_Detail SET" +
                                "  nReceived = nReceived + " + (fbSave == true ? loRS.getInt("nQuantity") : (loRS.getInt("nQuantity") * -1)) +
                                ", nCancelld = nQuantity - nReceived" + 
                                ", dModified = " + SQLUtil.toSQL(poGRider.getServerDate()) +
                            " WHERE sTransNox = " + SQLUtil.toSQL(loRS.getString("xOrderNox")) +
                                " AND sStockIDx = " + SQLUtil.toSQL(loRS.getString("xStockIDx"));
                    
                    poGRider.executeQuery(lsSQL, "PO_Detail", poGRider.getBranchCode(), "");
                }          
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
            setErrMsg(ex.getMessage());
            return false;
        }
        
        return true;
    }
    
    private boolean unsaveInvTrans(String fsTransNox, String fsSupplier){
        InventoryTrans loInvTrans = new InventoryTrans(poGRider, poGRider.getBranchCode());
        loInvTrans.InitTransaction();
        
        for (int lnCtr = 0; lnCtr <= poDetail.size() - 1; lnCtr ++){
            loInvTrans.setDetail(lnCtr, "sStockIDx", poDetail.get(lnCtr).getStockID());
            loInvTrans.setDetail(lnCtr, "nQtyOnHnd", paDetailOthers.get(lnCtr).getValue("nQtyOnHnd"));
            loInvTrans.setDetail(lnCtr, "nResvOrdr", paDetailOthers.get(lnCtr).getValue("nResvOrdr"));
            loInvTrans.setDetail(lnCtr, "nBackOrdr", paDetailOthers.get(lnCtr).getValue("nBackOrdr"));
            loInvTrans.setDetail(lnCtr, "nLedgerNo", paDetailOthers.get(lnCtr).getValue("nLedgerNo"));
        }
        
        if (!loInvTrans.POReceiving(fsTransNox, poGRider.getServerDate(), fsSupplier, EditMode.DELETE)){
            setMessage(loInvTrans.getMessage());
            setErrMsg(loInvTrans.getErrMsg());
            return false;
        }
        
        //TODO
            //update branch order info
    
        return true;
    }
    
    public void setGRider(GRider foGRider){
        this.poGRider = foGRider;
        this.psUserIDxx = foGRider.getUserID();
        
        if (psBranchCd.isEmpty()) psBranchCd = foGRider.getBranchCode();
        
        poDetail = new ArrayList<>();
    }
    
    public void setUserID(String fsUserID){
        this.psUserIDxx  = fsUserID;
    }
    
    private Connection setConnection(){
        Connection foConn;
        
        if (pbWithParent){
            foConn = (Connection) poGRider.getConnection();
            if (foConn == null) foConn = (Connection) poGRider.doConnect();
        }else foConn = (Connection) poGRider.doConnect();
        
        return foConn;
    }
    
    //Member Variables
    private GRider poGRider = null;
    private String psUserIDxx = "";
    private String psBranchCd = "";
    private String psWarnMsg = "";
    private String psErrMsgx = "";
    private boolean pbWithParent = false;
    private final GCrypt poCrypt = new GCrypt();
    
    private ArrayList<UnitPOReceivingDetail> poDetail;
    private ArrayList<UnitPOReceivingDetailOthers> paDetailOthers;
    private final String pxeMasTable = "PO_Receiving_Master";
    private final String pxeDetTable = "PO_Receiving_Detail";
    private final String pxeModuleName = POReceiving.class.getSimpleName();
    
    @Override
    public boolean closeTransaction(String string) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }
}