package com.nju.aop.controller;

import com.nju.aop.dataobject.*;
import com.nju.aop.dto.ChemicalInfo;
import com.nju.aop.dto.ToxDTO;
import com.nju.aop.repository.BioassayRepository;
import com.nju.aop.repository.ChainRepository;
import com.nju.aop.repository.ChemicalBriefRepository;
import com.nju.aop.repository.ToxRepository;
import com.nju.aop.utils.PageUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * created by Kimone
 * date 2019/12/26
 */
@RestController
@Slf4j
@RequestMapping("/api/tox")
public class ToxController {
    @Autowired
    private ToxRepository toxRepository;
    @Autowired
    private ChainRepository chainRepository;
    @Autowired
    private BioassayRepository bioassayRepository;
    @Autowired
    private ChemicalBriefRepository chemicalBriefRepository;

    @GetMapping("/{queryName}")
    public Page<ToxDTO> getByQueryName(@PathVariable String queryName, Pageable pageable) {
        Page<Tox> res = toxRepository.findByCasrnOrChemical(queryName,queryName,pageable);
        List<ToxDTO> toxDTOS = judge(res.getContent());
        return PageUtil.listConvertToPageWithOnePage(toxDTOS, pageable, res.getTotalElements());
    }

    @GetMapping("/all")
    public Page<ToxDTO> findAll(Pageable pageable){
        Page<Tox> toxList = toxRepository.findAll(pageable);
        List<ToxDTO> toxDTOS = judge(toxList.getContent());
        return PageUtil.listConvertToPageWithOnePage(toxDTOS, pageable, toxList.getTotalElements());
    }

    private List<ToxDTO> judge(List<Tox> toxList) {
        List<Bioassay> bioassays = bioassayRepository.findAll();
        Set set = bioassays.stream().map(b->b.getBioassayName()+b.getEffect()).collect(Collectors.toSet());
        List<ToxDTO> list = toxList.stream().map(t->{
            ToxDTO toxDTO = new ToxDTO();
            BeanUtils.copyProperties(t, toxDTO);
            String[] bioNames = t.getBioassay().split(",");
            for(int i = 0; i < bioNames.length; i++) {
                if(set.contains(bioNames[i]+t.getEffect())) {
                    toxDTO.setHasRes(true);
                    break;
                }
            }
            return toxDTO;
        }).collect(Collectors.toList());
        Comparator<ToxDTO> comparator = (tox1, tox2) -> {
            if(tox1.isHasRes() ^ tox2.isHasRes()) {
                return tox1.isHasRes()?-1:1;
            }else {
                return 0;
            }
        };
        list.sort(comparator);
        return list;
    }

    @PostMapping("/diagnose")
    public Map<Integer, Set<ChemicalInfo>> diagnose(@RequestBody List<Aop> aops) {
        Map<Integer, Set<ChemicalInfo>> map = new HashMap<>();
        List<Integer> aopIDList = aops.stream().map(Aop::getId).collect(Collectors.toList());
        StringBuilder builder = new StringBuilder();
        for(int aopId: aopIDList) {
            Set<ChemicalInfo> chemicals = new HashSet<>(); //每一个AOP对应的化学品的集合
            List<Chain> ownedNodes = chainRepository.findByAopId(aopId);
            for(Chain chain: ownedNodes) {
                List<Bioassay> bioassays = bioassayRepository.findByEventId(chain.getEventId());
                for(Bioassay bioassay: bioassays) {
                    List<Tox> toxList;
                    String bioassayName = bioassay.getBioassayName();
                    String effect = bioassay.getEffect();
                    if(StringUtils.isEmpty(effect)) {
                        toxList = toxRepository.findByBioassayOrBioassayLikeOrBioassayLikeOrBioassayLike(
                                bioassayName,"%,"+bioassayName,bioassayName+",%","%,"+bioassayName+",%");
                    }else {
                        toxList = toxRepository.findByBioassayAndEffect(
                                bioassayName,"%,"+bioassayName,bioassayName+",%","%,"+bioassayName+",%",effect);
                    }
                    for(Tox tox: toxList) {
                        ChemicalBrief chemicalBrief = chemicalBriefRepository.findByCasAndEnglish(tox.getCasrn(), tox.getChemical());
                        String beInChina = chemicalBrief==null?"/":chemicalBrief.getBeInChina()+"";
                        chemicals.add(new ChemicalInfo(tox.getCasrn(),tox.getChemical(),beInChina));
                    }
                }
            }
            map.put(aopId,chemicals);
            builder.append(aopId+"_");
        }
        saveExcel(map, builder.toString());
        return map;
    }

    private void saveExcel(Map<Integer, Set<ChemicalInfo>> map, String name){
        XSSFWorkbook workbook = new XSSFWorkbook();
        XSSFSheet sheet = workbook.createSheet();
        XSSFRow firstRow = sheet.createRow(0);
        String[] heads = {"AOP ID","化学品CAS","化学品英文名","我国有无\n" +
                "(0我国无记录化学品；1我国有记录普通化学品；2优控化学品)"};
        XSSFCell[] firstCells = new XSSFCell[heads.length];
        for(int i = 0; i < heads.length; i++) {
            firstCells[i] = firstRow.createCell(i);
            firstCells[i].setCellValue(heads[i]);
        }
        int curRowNum = 1;
        for(Integer aopId: map.keySet()) {
            Set<ChemicalInfo> set = map.get(aopId);
            if(set.size() > 1) {
                CellRangeAddress aopIdRegion = new CellRangeAddress(curRowNum, curRowNum+set.size()-1,0,0);
                sheet.addMergedRegion(aopIdRegion);
                int i=0;
                for(ChemicalInfo chemicalInfo: set) {
                    XSSFRow row = sheet.createRow(curRowNum+i++);
                    row.createCell(1).setCellValue(chemicalInfo.getCas());
                    row.createCell(2).setCellValue(chemicalInfo.getName());
                    row.createCell(3).setCellValue(chemicalInfo.getBeInChina());
                }
                sheet.getRow(curRowNum).createCell(0).setCellValue(aopId);
                curRowNum+=set.size();
            }else {
                XSSFRow row = sheet.createRow(curRowNum++);
                for(ChemicalInfo chemicalInfo: set) {
                    row.createCell(0).setCellValue(aopId);
                    row.createCell(1).setCellValue(chemicalInfo.getCas());
                    row.createCell(2).setCellValue(chemicalInfo.getName());
                    row.createCell(3).setCellValue(chemicalInfo.getBeInChina());
                }
            }
        }

        name += ".xlsx";
        String filePath = File.separator + "tmp" + File.separator + "resource" + File.separator + "static" + File.separator + "resultFiles" + File.separator + name;
//        String filePath = "E:\\resultFiles\\"+name;
        File file = new File(filePath);
        File dir = file.getParentFile();
        if(!dir.exists()) {
            dir.mkdirs();
        }

        if(file.exists()) {
            file.delete();
        }
        try {
            file.createNewFile();
            OutputStream os = new FileOutputStream(file);
            workbook.write(os);
            os.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
