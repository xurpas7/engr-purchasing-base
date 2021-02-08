package org.rmj.engr.purchasing.base;

import com.mysql.jdbc.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.rmj.appdriver.GCrypt;
import org.rmj.appdriver.GRider;
import org.rmj.appdriver.iface.GEntity;
import org.rmj.appdriver.iface.GTransaction;
import org.rmj.appdriver.MiscUtil;
import org.rmj.appdriver.SQLUtil;
import org.rmj.appdriver.constants.EditMode;
import org.rmj.appdriver.constants.TransactionStatus;
import org.rmj.cas.inventory.base.InvMaster;
import org.rmj.cas.inventory.base.InventoryTrans;
import org.engr.purchasing.pojo.UnitPOReceivingMaster;
import org.engr.purchasing.pojo.UnitPOReturnDetail;
import org.engr.purchasing.pojo.UnitPOReturnMaster;

public class POReturn implements GTransaction{
    @Override
    public UnitPOReturnMaster newTransaction() {
        UnitPOReturnMaster loObj = new UnitPOReturnMaster();
        Connection loConn = null;
        loConn = setConnection();
        
        loObj.setTransNox(MiscUtil.getNextCode(loObj.getTable(), "sTransNox", true, loConn, psBranchCd));
        
        //init detail
        poDetail = new ArrayList<>();
        
        return loObj;
    }

    @Override
    public UnitPOReturnMaster loadTransaction(String fsTransNox) {
        UnitPOReturnMaster loObject = new UnitPOReturnMaster();
        
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
    public UnitPOReturnMaster saveUpdate(Object foEntity, String fsTransNox) {
        String lsSQL = "";
        
        UnitPOReturnMaster loOldEnt = null;
        UnitPOReturnMaster loNewEnt = null;
        UnitPOReturnMaster loResult = null;
        
        // Check for the value of foEntity
        if (!(foEntity instanceof UnitPOReturnMaster)) {
            setErrMsg("Invalid Entity Passed as Parameter");
            return loResult;
        }
        
        // Typecast the Entity to this object
        loNewEnt = (UnitPOReturnMaster) foEntity;
        
        
        // Test if entry is ok
        if (loNewEnt.getBranchCd()== null || loNewEnt.getBranchCd().isEmpty()){
            setMessage("Invalid project name detected.");
            return loResult;
        }
        
        if (loNewEnt.getDateTransact()== null){
            setMessage("Invalid transact date detected.");
            return loResult;
        }
        
        if (loNewEnt.getInvTypeCd()== null || loNewEnt.getInvTypeCd().isEmpty()){
            setMessage("Invalid inventory type detected.");
            return loResult;
        }
               
        if (!pbWithParent) poGRider.beginTrans();
        
        // Generate the SQL Statement
        if (fsTransNox.equals("")){
            try {
                Connection loConn = null;
                loConn = setConnection();
                
                String lsTransNox = MiscUtil.getNextCode(loNewEnt.getTable(), "sTransNox", true, loConn, psBranchCd);
                
                loNewEnt.setTransNox(lsTransNox);
                loNewEnt.setModifiedBy(poCrypt.encrypt(psUserIDxx));
                loNewEnt.setDateModified(poGRider.getServerDate());
                
                loNewEnt.setPreparedBy(poCrypt.encrypt(psUserIDxx));
                loNewEnt.setDatePrepared(poGRider.getServerDate());
                
                //save detail first
                saveDetail(lsTransNox, true);
                
                loNewEnt.setEntryNox(ItemCount());
                
                if (!pbWithParent) MiscUtil.close(loConn);
                
                //Generate the SQL Statement
                lsSQL = MiscUtil.makeSQL((GEntity) loNewEnt);
            } catch (SQLException ex) {
                Logger.getLogger(POReturn.class.getName()).log(Level.SEVERE, null, ex);
            }
        }else{
            try {
                //Load previous transaction
                loOldEnt = loadTransaction(fsTransNox);
                
                //save detail first
                saveDetail(fsTransNox, true);
                
                loNewEnt.setEntryNox(ItemCount());
                loNewEnt.setDateModified(poGRider.getServerDate());
                
                //Generate the Update Statement
                lsSQL = MiscUtil.makeSQL((GEntity) loNewEnt, (GEntity) loOldEnt, "sTransNox = " + SQLUtil.toSQL(loNewEnt.getTransNox()));
            } catch (SQLException ex) {
                Logger.getLogger(POReturn.class.getName()).log(Level.SEVERE, null, ex);
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
        UnitPOReturnMaster loObject = loadTransaction(fsTransNox);
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
        UnitPOReturnMaster loObject = loadTransaction(fsTransNox);
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
                            ", sApproved = " + SQLUtil.toSQL(poCrypt.encrypt(psUserIDxx)) +
                            ", dApproved = " + SQLUtil.toSQL(poGRider.getServerDate()) + 
                            ", sAprvCode = " + SQLUtil.toSQL(fsApprovalCode) +
                            ", sModified = " + SQLUtil.toSQL(poCrypt.encrypt(psUserIDxx)) +
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
    public boolean postTransaction(String fsTransNox) {
        UnitPOReturnMaster loObject = loadTransaction(fsTransNox);
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
                            ", sPostedxx = " + SQLUtil.toSQL(poCrypt.encrypt(psUserIDxx)) +
                            ", dPostedxx = " + SQLUtil.toSQL(poGRider.getServerDate()) + 
                            ", sModified = " + SQLUtil.toSQL(poCrypt.encrypt(psUserIDxx)) +
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
            } else poGRider.rollbackTrans();
        }
        return lbResult;
    }

    @Override
    public boolean voidTransaction(String fsTransNox) {
        UnitPOReturnMaster loObject = loadTransaction(fsTransNox);
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
        UnitPOReturnMaster loObject = loadTransaction(fsTransNox);
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
                    ", nTranTotl" +
                    ", nVATRatex" +
                    ", nTWithHld" +
                    ", nDiscount" +
                    ", nAddDiscx" +
                    ", nFreightx" +
                    ", sRemarksx" +
                    ", nAmtPaidx" +
                    ", sSourceNo" +
                    ", sSourceCd" +
                    ", sPOTransx" +
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
    
    //Added detail methods
    //Added methods
    private boolean saveInvTrans(UnitPOReturnMaster foValue){
        InventoryTrans loInvTrans = new InventoryTrans(poGRider, foValue.getBranchCd());
        loInvTrans.InitTransaction();
        
        InvMaster loInv = new InvMaster(poGRider, psBranchCd, true);
        
        for (int lnCtr = 0; lnCtr <= poDetail.size() - 1; lnCtr ++){
            if (poDetail.get(lnCtr).getStockID().equals("")) break;
            
            if (loInv.SearchInventory(poDetail.get(lnCtr).getStockID(), false, true)){
                loInvTrans.setDetail(lnCtr, "sStockIDx", loInv.getMaster("sStockIDx"));
                loInvTrans.setDetail(lnCtr, "sReplacID", "");
                loInvTrans.setDetail(lnCtr, "nQuantity", poDetail.get(lnCtr).getQuantity());
                loInvTrans.setDetail(lnCtr, "nQtyOnHnd", loInv.getMaster("nQtyOnHnd"));
                loInvTrans.setDetail(lnCtr, "nResvOrdr", loInv.getMaster("nResvOrdr"));
                loInvTrans.setDetail(lnCtr, "nBackOrdr", loInv.getMaster("nBackOrdr"));
                loInvTrans.setDetail(lnCtr, "nLedgerNo", loInv.getMaster("nLedgerNo"));
            } else {
                setErrMsg("No Inventory Found.");
                setMessage("Unable to search item on inventory.");
                return false;
            }
        }
        
        if (!loInvTrans.POReturn(foValue.getTransNox(), poGRider.getServerDate(), foValue.getSupplier(), EditMode.ADDNEW)){
            setMessage(loInvTrans.getMessage());
            setErrMsg(loInvTrans.getErrMsg());
            return false;
        }
        return true;
    }
    
    private boolean unsaveInvTrans(String fsTransNox, String fsSupplier){
        InventoryTrans loInvTrans = new InventoryTrans(poGRider, poGRider.getBranchCode());
        loInvTrans.InitTransaction();
        
        InvMaster loInv = new InvMaster(poGRider, psBranchCd, true);
        
        for (int lnCtr = 0; lnCtr <= poDetail.size() - 1; lnCtr ++){
            if (poDetail.get(lnCtr).getStockID().equals("")) break;
            
            if (loInv.SearchInventory(poDetail.get(lnCtr).getStockID(), false, true)){
                loInvTrans.setDetail(lnCtr, "sStockIDx", loInv.getMaster("sStockIDx"));
                loInvTrans.setDetail(lnCtr, "nQtyOnHnd", loInv.getMaster("nQtyOnHnd"));
                loInvTrans.setDetail(lnCtr, "nResvOrdr", loInv.getMaster("nResvOrdr"));
                loInvTrans.setDetail(lnCtr, "nBackOrdr", loInv.getMaster("nBackOrdr"));
                loInvTrans.setDetail(lnCtr, "nLedgerNo", loInv.getMaster("nLedgerNo"));
            } else {
                setErrMsg("No Inventory Found.");
                setMessage("Unable to search item on inventory.");
                return false;
            }
            
            
        }
        
        if (!loInvTrans.POReturn(fsTransNox, poGRider.getServerDate(), fsSupplier, EditMode.DELETE)){
            setMessage(loInvTrans.getMessage());
            setErrMsg(loInvTrans.getErrMsg());
            return false;
        }
        
        //TODO
            //update branch order info
    
        return true;
    }
    
    public int ItemCount(){
        return poDetail.size();
    }
    
    public boolean addDetail() {
        if (poDetail.isEmpty()){
            poDetail.add(new UnitPOReturnDetail());
        }else{
            if (!poDetail.get(ItemCount()-1).getStockID().equals("") && 
                poDetail.get(ItemCount() -1).getQuantity() != 0){
                poDetail.add(new UnitPOReturnDetail());
            } else return false; 
        }
        return true;
    }

    public boolean deleteDetail(int fnEntryNox) {
        poDetail.remove(fnEntryNox);
        
        if (poDetail.isEmpty() ||
            !poDetail.get(ItemCount() -1).getStockID().equals("")) return addDetail();
        
        return true;
    }
    
    public void setDetail(int fnRow, int fnCol, Object foData){
        switch (fnCol){
            case 6: //nUnitPrce
            case 7: //nFreightx
                if (foData instanceof Number){
                    poDetail.get(fnRow).setValue(fnCol, foData);
                }else poDetail.get(fnRow).setValue(fnCol, 0);
                break;
            case 5: //nQuantity
                if (foData instanceof Integer){
                    poDetail.get(fnRow).setValue(fnCol, foData);
                }else poDetail.get(fnRow).setValue(fnCol, 0);
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
            default:
                poDetail.get(fnRow).setValue(fsCol, foData);
        }
    }
    
    public Object getDetail(int fnRow, int fnCol){return poDetail.get(fnRow).getValue(fnCol);}
    public Object getDetail(int fnRow, String fsCol){return poDetail.get(fnRow).getValue(fsCol);}
    
    private boolean saveDetail(String fsTransNox, boolean fbNewRecord) throws SQLException{
        if (ItemCount() <= 0){
            setMessage("No transaction detail detected.");
            return false;
        }
        
        UnitPOReturnDetail loDetail;
        UnitPOReturnDetail loOldDet;
        int lnCtr;
        String lsSQL;
        
        for (lnCtr = 0; lnCtr <= ItemCount() -1; lnCtr++){
            if (lnCtr == 0){
                if (poDetail.get(lnCtr).getStockID() == null || poDetail.get(lnCtr).getStockID().isEmpty()){
                    setMessage("Invalid stock id detected.");
                    return false;
                }
            }else {
                if (poDetail.get(lnCtr).getStockID() == null || poDetail.get(lnCtr).getStockID().isEmpty()){ 
                    poDetail.remove(lnCtr);
                    return true;
                }
            }
            
            poDetail.get(lnCtr).setTransNox(fsTransNox);
            poDetail.get(lnCtr).setEntryNox(lnCtr + 1);
            poDetail.get(lnCtr).setDateModified(poGRider.getServerDate());
            
            if (fbNewRecord){
                //Generate the SQL Statement
                lsSQL = MiscUtil.makeSQL((GEntity) poDetail.get(lnCtr));
            }else{
                //Load previous transaction
                loOldDet = loadTransDetail(fsTransNox, lnCtr + 1);
            
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
                    setMessage("No record updated");
                }
            }
        }    
        
        //check if the new detail is less than the original detail count
        int lnRow = loadTransDetail(fsTransNox).size();
        if (lnCtr < lnRow -1){
            for (lnCtr = lnCtr + 1; lnCtr <= lnRow; lnCtr++){
                lsSQL = "DELETE FROM " + pxeDetTable +  
                        " WHERE sTransNox = " + SQLUtil.toSQL(fsTransNox) + 
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
    
    private UnitPOReturnDetail loadTransDetail(String fsTransNox, int fnEntryNox) throws SQLException{
        UnitPOReturnDetail loObj = null;
        ResultSet loRS = poGRider.executeQuery(
                            MiscUtil.addCondition(getSQ_Detail(), 
                                                    "sTransNox = " + SQLUtil.toSQL(fsTransNox)) + 
                                                    " AND nEntryNox = " + fnEntryNox);
        
        if (!loRS.next()){
            setMessage("No Record Found");
        }else{
            //load each column to the entity
            loObj = new UnitPOReturnDetail();
            for(int lnCol=1; lnCol<=loRS.getMetaData().getColumnCount(); lnCol++){
                loObj.setValue(lnCol, loRS.getObject(lnCol));
            }
        }      
        return loObj;
    }
    
    private ArrayList<UnitPOReturnDetail> loadTransDetail(String fsTransNox) throws SQLException{
        UnitPOReturnDetail loOcc = null;
        Connection loConn = null;
        loConn = setConnection();
        
        ArrayList<UnitPOReturnDetail> loDetail = new ArrayList<>();
        ResultSet loRS = poGRider.executeQuery(
                            MiscUtil.addCondition(getSQ_Detail(), 
                                                    "sTransNox = " + SQLUtil.toSQL(fsTransNox)));
        
        for (int lnCtr = 1; lnCtr <= MiscUtil.RecordCount(loRS); lnCtr ++){
            loRS.absolute(lnCtr);
            
            loOcc = new UnitPOReturnDetail();
            
            for(int lnCol=1; lnCol<=loRS.getMetaData().getColumnCount(); lnCol++){
                loOcc.setValue(lnCol, loRS.getObject(lnCol));
            }

            loDetail.add(loOcc);
        }
        
        return loDetail;
    }
    
    private String getSQ_Detail(){
        return "SELECT" +
                    "  sTransNox" +
                    ", nEntryNox" +
                    ", sStockIDx" +
                    ", cUnitType" +
                    ", nQuantity" +
                    ", nUnitPrce" +
                    ", nFreightx" +
                    ", dModified" +
                " FROM " + pxeDetTable +
                " ORDER BY nEntryNox";
    }
    
    //Added methods
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
    
    private ArrayList<UnitPOReturnDetail> poDetail;
    private final String pxeMasTable = "PO_Return_Master";
    private final String pxeDetTable = "PO_Return_Detail";

    @Override
    public boolean closeTransaction(String string) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }
}