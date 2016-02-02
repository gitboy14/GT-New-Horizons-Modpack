package com.dreammaster.modlootbags;

import java.io.File;
import java.io.FileOutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.EnumRarity;
import net.minecraft.item.Item;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.DimensionManager;

import com.dreammaster.auxiliary.ItemHelper;
import com.dreammaster.lib.Refstrings;
import com.dreammaster.main.MainRegistry;
import com.dreammaster.modlootbags.LootGroups.LootGroup;
import com.dreammaster.modlootbags.LootGroups.LootGroup.Drop;
import com.dreammaster.network.msg.LootBagClientSyncMessage;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.registry.GameRegistry;
import eu.usrv.yamcore.auxiliary.LogHelper;
import eu.usrv.yamcore.persisteddata.PersistedDataBase;

public class LootGroupsHandler
{
	private LogHelper _mLogger = MainRegistry.Logger;
	private String _mConfigFileName;
    private LootGroupsFactory _mLGF = new LootGroupsFactory();
    private LootGroups _mLootGroups = null;
    private eu.usrv.yamcore.persisteddata.PersistedDataBase _mPersistedDB = null;

    private boolean _mInitialized = false;
    
    public LootGroups getLootGroups()
    {
    	return _mLootGroups;
    }
    
    /**
     * Calculate the unique loot-identifier for given loot and player. This is used to keep track of stuff the
     * player got already
     * @param pPlayer
     * @param pDrop
     * @return
     */
    public String getUniqueLootIdentifier(EntityPlayer pPlayer, LootGroup pGroup, Drop pDrop)
    {
    	return String.format("%s_%s_%s", pPlayer.getUniqueID().toString(), pGroup.mGroupID, pDrop.mDropID);
    }
    
    /**
     * Check if pPlayer is allowed to receive item pDrop in group pGroup
     * @param pPlayer
     * @param pGroup
     * @param pDrop
     * @return
     */
    public boolean isDropAllowedForPlayer(EntityPlayer pPlayer, LootGroup pGroup, Drop pDrop)
    {
    	if (_mPersistedDB == null)
    	{
    		_mPersistedDB = new PersistedDataBase(DimensionManager.getCurrentSaveRootDirectory(),
    				"LootBags.dat",
    				Refstrings.COLLECTIONID);
    	}
	
    	boolean tResult = true;
    	String pDropUID = getUniqueLootIdentifier(pPlayer, pGroup, pDrop);
    	//FMLLog.info("pDropUID: %s", pDropUID);
    	
    	if (pDrop.getLimitedDropCount() > 0)
    	{
    		int tReceivedAmount = _mPersistedDB.getValueAsInt(pDropUID, 0);
    		//FMLLog.info("tReceivedAmount: %d", tReceivedAmount);
    		if (tReceivedAmount >= pDrop.getLimitedDropCount())
    			tResult = false;
    		else
    			_mPersistedDB.setValue(pDropUID, tReceivedAmount+1);
    	}

    	return tResult;
    }
    
    public LootGroupsHandler(File pConfigBaseDir)
	{
    	_mConfigFileName = String.format("config/%s/LootBags.xml", Refstrings.COLLECTIONID);
	}
    
    /**
     * Init sample configuration if none could be found
     */
    public void InitSampleConfig()
    {
    	Drop pigDiamondLimitedDrop = _mLGF.createDrop("minecraft:diamond", "sample_Loot_DiamondDrop", "{display:{Lore:[\"Oh, shiny\"]}}", 1, false, 100, 5);
    	Drop pigCakeUnlimitedDrop = _mLGF.createDrop("minecraft:cake", "sample_Loot_CakeDrop", 1, false, 100, 0);
    	Drop pigRandomCharcoalDrop = _mLGF.createDrop("minecraft:coal:1", "sample_Loot_CharcoalDrop", 5, true, 100, 0);
  
    	LootGroup tSampleGroup = _mLGF.createLootGroup(1, "SampleGroup", EnumRarity.common, 1, 1);
    	tSampleGroup.getDrops().add(pigDiamondLimitedDrop);
    	tSampleGroup.getDrops().add(pigCakeUnlimitedDrop);
    	tSampleGroup.getDrops().add(pigRandomCharcoalDrop);
    	
    	_mLootGroups = new LootGroups();
    	_mLootGroups.getLootTable().add(tSampleGroup);
    }
    
	public LootGroup getGroupByID(int pGroupID)
	{
		for (LootGroup tGrp : _mLootGroups.getLootTable())
			if (tGrp.mGroupID == pGroupID)
				return tGrp;
		return null;
	}
    
    /**
     * Save the loot configuration to disk
     * @return
     */
    public boolean SaveLootGroups()
    {
        try
        {
            JAXBContext tJaxbCtx = JAXBContext.newInstance(LootGroups.class);
            Marshaller jaxMarsh = tJaxbCtx.createMarshaller();
            jaxMarsh.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true); 
            jaxMarsh.marshal(_mLootGroups, new FileOutputStream(_mConfigFileName, false));

            _mLogger.debug("[LootBags] Config file written");
            return true;
        } catch (Exception e)
        {
            _mLogger.error("[LootBags] Unable to create new LootBags.xml. What did you do??");
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Load the loot configuration from disk. Will not overwrite the existing loottable if errors occour
     * Only called ONCE! Upon PostLoad(). Call reload() instead
     */
    public void LoadConfig()
    {
    	if (_mInitialized)
    	{
    		_mLogger.error("[LootBags] Something just called LoadConfig AFTER it has been initialized!");
    		return;
    	}
    	
        _mLogger.debug("[LootBags] LootBags entering state: LOAD CONFIG");
        File tConfigFile = new File(_mConfigFileName);
        if (!tConfigFile.exists())
        {
            _mLogger.debug("[LootBags] LootBags Config file not found, assuming first-start. Creating default one");
            InitSampleConfig();
            SaveLootGroups();
        }

        // Fix for broken XML file; If it can't be loaded on reboot, keep it
        // there to be fixed, but load
        // default setting instead, so an Op/Admin can do reload ingame
        if (!ReloadLootGroups(""))
        {
            _mLogger.warn("[LootBags] Configuration File seems to be damaged, nothing will be loaded!");
            MainRegistry.AddLoginError("[LootBags] Config file not loaded due errors");
            InitSampleConfig();
        }
        _mInitialized = true;
    }
    
    /**
     * Initiate reload. Will reload the config from disk and replace
     * the internal list. If the file contains errors, nothing will be replaced, and
     * an errormessage will be sent to the command issuer.
     * 
     * This method will just load the config the first time it is called, as this will happen
     * in the servers load/postinit phase. After that, every call is caused by someone who tried to
     * do an ingame reload. If that is successful, the updated config is broadcasted to every
     * connected client
     * @return
     */
    public boolean reload()
    {
    	boolean tState = ReloadLootGroups("");
    	if (_mInitialized)
    	{
	    	if (tState)
	    		sendClientUpdate();
	    	else
	    		_mLogger.error("[LootBags] Reload of LootBag file failed. Not sending client update");
    	}    	
    	return tState;
    }
    
    public static Item mLootBagItem = null;
    public void registerBagItem()
    {
    	mLootBagItem = new ItemLootBag(this);
    	GameRegistry.registerItem(mLootBagItem, "lootbag");
    }
    
    private String getXMLStream()
    {
        try
        {
        	StringWriter tSW = new StringWriter();
            JAXBContext tJaxbCtx = JAXBContext.newInstance(LootGroups.class);
            Marshaller jaxMarsh = tJaxbCtx.createMarshaller();
            jaxMarsh.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true); 
            jaxMarsh.marshal(_mLootGroups, tSW);

            return tSW.toString();
        } catch (Exception e)
        {
            _mLogger.error("[LootBags] Unable to serialize object");
            e.printStackTrace();
            return "";
        }
    }
    
    /**
     * Verify the loaded config and report errors if found
     * @param pLootGroupsToCheck
     * @return
     */
    private boolean VerifyConfig(LootGroups pLootGroupsToCheck)
    {
    	boolean tSuccess = true;
    	List<Integer> tIDlist = new ArrayList<Integer>();
    	List<String> tNameList = new ArrayList<String>();
    	
    	for (LootGroup X : pLootGroupsToCheck.getLootTable())
    	{
    		if (tIDlist.contains(X.mGroupID))
    		{
    			_mLogger.error(String.format("[LootBags] LootGroup ID %d already exists!", X.mGroupID));
    			tSuccess = false;
    			break;
    		}
    		else
    			tIDlist.add(X.mGroupID);
    		
    		if (tNameList.contains(X.mGroupName))
    		{
    			_mLogger.error(String.format("[LootBags] LootGroup with the Name %s already exists!", X.mGroupName));
    			tSuccess = false;
    			break;
    		}
    		else
    			tNameList.add(X.mGroupName);    			
    			
    		if (X.mDrops.size() == 0)
    		{
    			_mLogger.error(String.format("[LootBags] LootGroup ID %d is empty!", X.mGroupID));
    			tSuccess = false;
    			break;
    		}
    		
    		for (Drop Y : X.getDrops())
    		{
    			if (ItemHelper.ConvertStringToItem(Y.getItemName()) == null)
    			{
    				_mLogger.error(String.format("[LootBags] In ItemDropID: [%s], can't find item [%s]", Y.getIdentifier(), Y.getItemName()));
    				tSuccess = false;
    			}
    			
    			if (Y.mTag != null && !Y.mTag.isEmpty())
    			{
        			try
        			{
        			    NBTTagCompound tNBT = (NBTTagCompound) JsonToNBT.func_150315_a(Y.mTag);
        			    if (tNBT == null)
        			        tSuccess = false;
        			}
        			catch (Exception e)
        			{
        			    _mLogger.error(String.format("[LootBags] In ItemDropID: [%s], NBTTag is invalid", Y.getIdentifier()));
        			    tSuccess = false;
        			}
    			}
    		}
    	}
    	return tSuccess;
    }

    
    /**
     * (Re)load lootgroups 
     * @return
     */
    private boolean ReloadLootGroups(String pXMLContent)
    {
        boolean tResult = false;

        _mLogger.debug("[LootBags] LootGroupsHandler will now try to load its configuration");
        try
        {
            JAXBContext tJaxbCtx = JAXBContext.newInstance(LootGroups.class);
            Unmarshaller jaxUnmarsh = tJaxbCtx.createUnmarshaller();
            
            LootGroups tNewItemCollection = null;
            
            if (pXMLContent.isEmpty())
            {
                File tConfigFile = new File(_mConfigFileName);
                tNewItemCollection = (LootGroups) jaxUnmarsh.unmarshal(tConfigFile);
                _mLogger.debug("[LootBags] Config file has been loaded. Entering Verify state");
            }
            else
            {
            	StringReader reader = new StringReader(pXMLContent);
            	tNewItemCollection = (LootGroups) jaxUnmarsh.unmarshal(reader);
            	_mLogger.debug("[LootBags] Received Server-Config. Entering Verify state");
            }
            
            if (!VerifyConfig(tNewItemCollection))
            {
            	_mLogger.error("[LootBags] New config will NOT be activated. Please check your error-log and try again");
            	tResult = false;
            }
            else
            {
            	_mLootGroups = tNewItemCollection;
            	tResult = true;
            }

        } catch (Exception e)
        {
            e.printStackTrace();
        }

        return tResult;
    }

    /**
     * SERVERSIDE
     * @param pEvent
     */
    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent pEvent)
    {
    	if (pEvent.player instanceof EntityPlayerMP)
    		sendClientUpdate((EntityPlayerMP)pEvent.player);
    }

    private void sendClientUpdate()
    {
    	sendClientUpdate(null);
    }
    
    /**
     * SERERSIDE
     * Send client update with changed definition. set pPlayer to null to send to everyone on the server
     * @param pPlayer
     */
    private void sendClientUpdate(EntityPlayerMP pPlayer)
    {
		String tPayload = getXMLStream();
		if (!tPayload.isEmpty())
		{
			if (pPlayer != null && pPlayer instanceof EntityPlayerMP)
				MainRegistry.NW.sendTo(new LootBagClientSyncMessage(tPayload), pPlayer);
			else if (pPlayer == null)
				MainRegistry.NW.sendToAll(new LootBagClientSyncMessage(tPayload));
			else
				_mLogger.error("[LootBags.sendClientUpdate] Target is no EntityPlayer and not null");	
		}
		else
			_mLogger.error("[LootBags] Unable to send update to clients; Received empty serialized object");		
	}
    
	/**
	 * CLIENTSIDE
	 * This is called when the server has sent an update
	 * @param pPayload
	 */
	public void processServerConfig(String pPayload)
	{
    	if (ReloadLootGroups(pPayload))
    		_mLogger.info("[LootBags] Received and activated configuration from server");
    	else
    		_mLogger.warn("[LootBags] Received invalid configuration from server; Not activated!");
	}
}
